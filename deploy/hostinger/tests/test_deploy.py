"""Test transactions without contacting Docker, GitHub, the VPS or production data."""
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deployment', Path(__file__).parents[1] / 'deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'deploy'
        (self.state / 'control').mkdir(parents=True)
        (self.state / 'history').mkdir()
        (self.root / 'secrets').mkdir()
        (self.root / 'secrets/deploy.env').write_text('DEPLOY_CONTROL_TOKEN=' + 'a' * 64 + '\n')
        for name, value in [('ROOT', self.root), ('STATE', self.state)]:
            p = patch.object(deploy, name, value)
            p.start()
            self.addCleanup(p.stop)
        self.previous = {'SPRING_IMAGE': 'talentengine-spring:bootstrap', 'FASTAPI_IMAGE': 'talentengine-fastapi:old'}
        deploy.write_images(self.previous)
        self.calls = []
        self.commit = 'c' * 40
        self.digest = 'sha256:' + 'd' * 64
        for name in ('command', 'compose', 'health', 'wait_drained'):
            p = patch.object(deploy, name)
            mock = p.start()
            setattr(self, name, mock)
            self.addCleanup(p.stop)
        self.command.return_value = json.dumps({'org.opencontainers.image.revision': self.commit,
                                               'org.opencontainers.image.source': 'https://github.com/Natanf0/talent-engine-spring-boot'})
        disk = patch.object(deploy.shutil, 'disk_usage')
        disk.start().return_value.free = 10 * 1024**3
        self.addCleanup(disk.stop)

    def run_deploy(self):
        deploy.deploy('spring', self.digest, self.commit, 7, 1)

    def test_success_changes_only_requested_component_and_records_previous(self):
        self.run_deploy()
        images = deploy.load_images()
        self.assertEqual(images['FASTAPI_IMAGE'], self.previous['FASTAPI_IMAGE'])
        self.assertEqual(images['SPRING_IMAGE'], 'ghcr.io/natanf0/talent-engine-spring-boot@' + self.digest)
        self.assertEqual(json.loads((self.state / 'previous.json').read_text()), self.previous)
        self.assertEqual(json.loads((self.state / 'orders.json').read_text()), {'spring': 7})
        self.assertFalse((self.state / 'control/maintenance').exists())
        self.assertFalse((self.state / 'pending.json').exists())
        self.assertIn('spring1', self.compose.call_args.args)
        self.assertIn('spring2', self.compose.call_args.args)
        self.assertNotIn('fastapi', self.compose.call_args.args)

    def test_failed_pull_does_not_enter_maintenance(self):
        self.command.side_effect = RuntimeError('pull failed')
        with self.assertRaises(RuntimeError):
            self.run_deploy()
        self.assertFalse((self.state / 'control/maintenance').exists())
        self.assertEqual(deploy.load_images(), self.previous)

    def test_drain_timeout_keeps_images_and_reopens_admission(self):
        self.wait_drained.side_effect = RuntimeError('timeout')
        with self.assertRaises(RuntimeError):
            self.run_deploy()
        self.assertEqual(deploy.load_images(), self.previous)
        self.assertFalse((self.state / 'control/maintenance').exists())
        self.assertFalse(any(c.args[0] == 'up' for c in self.compose.call_args_list))

    def test_unhealthy_release_rolls_back_without_network_or_rebuild(self):
        self.health.side_effect = [None, RuntimeError('unhealthy'), None]
        with self.assertRaises(RuntimeError):
            self.run_deploy()
        self.assertEqual(deploy.load_images(), self.previous)
        self.assertFalse((self.state / 'control/maintenance').exists())
        changes = [c for c in self.compose.call_args_list if c.args[0] == 'up']
        self.assertEqual(len(changes), 2)
        for call in changes:
            self.assertIn('--no-build', call.args)
            self.assertIn('never', call.args)

    def test_failed_rollback_keeps_durable_maintenance_and_blocks_next_deploy(self):
        self.health.side_effect = [None, RuntimeError('new failed'), RuntimeError('old failed')]
        with self.assertRaises(RuntimeError):
            self.run_deploy()
        self.assertTrue((self.state / 'control/maintenance').exists())
        self.assertTrue((self.state / 'pending.json').exists())
        with self.assertRaisesRegex(RuntimeError, 'Unfinished'):
            self.run_deploy()
        self.health.side_effect = None
        deploy.recover()
        self.assertFalse((self.state / 'control/maintenance').exists())
        self.assertEqual(deploy.load_images(), self.previous)

    def test_stale_run_and_duplicate_are_skipped(self):
        (self.state / 'orders.json').write_text('{"spring": 8}')
        self.run_deploy()
        self.command.assert_not_called()
        self.health.assert_not_called()

    def test_manual_rollback_preserves_monotonic_order(self):
        self.run_deploy()
        deploy.recover(rollback=True)
        self.assertEqual(deploy.load_images(), self.previous)
        self.assertEqual(json.loads((self.state / 'orders.json').read_text())['spring'], 7)

    def test_fastapi_deploy_does_not_recreate_spring(self):
        self.command.return_value = json.dumps({'org.opencontainers.image.revision': self.commit,
                                               'org.opencontainers.image.source': 'https://github.com/Natanf0/talent-engine-fast-api'})
        deploy.deploy('fastapi', self.digest, self.commit, 7, 1)
        self.assertEqual(deploy.load_images()['SPRING_IMAGE'], self.previous['SPRING_IMAGE'])
        self.assertEqual(self.compose.call_args.args[-1], 'fastapi')
        self.assertNotIn('spring1', self.compose.call_args.args)

    def test_shell_fragments_and_foreign_image_references_are_rejected(self):
        for args in [['spring', self.digest, self.commit, '1;id', '1'],
                     ['spring', 'ghcr.io/other/app:latest', self.commit, '1', '1'],
                     ['../shell', self.digest, self.commit, '1', '1']]:
            with self.assertRaises(ValueError):
                deploy.validate_args(args)

    def test_wait_requires_both_instances_and_counts_queued_work(self):
        # Call the original function through a fresh module to exercise polling.
        fresh = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fresh)
        busy = {'protocol': 1, 'draining': True, 'activeRequests': 0, 'pendingMatchings': 1}
        idle = dict(busy, pendingMatchings=0)
        with patch.object(fresh, 'status', side_effect=[idle, busy, idle, idle]) as status, \
                patch.object(fresh.time, 'sleep'):
            fresh.wait_drained('token')
        self.assertEqual([c.args[0] for c in status.call_args_list], [3000, 3001, 3000, 3001])

    def test_interruption_leaves_a_recoverable_transaction(self):
        def interrupt(*args, **kwargs):
            if args[0] == 'up':
                raise KeyboardInterrupt()
        self.compose.side_effect = interrupt
        with self.assertRaises(KeyboardInterrupt):
            self.run_deploy()
        self.assertTrue((self.state / 'pending.json').exists())
        self.assertTrue((self.state / 'control/maintenance').exists())
        self.compose.side_effect = None
        deploy.recover()
        self.assertEqual(deploy.load_images(), self.previous)
        self.assertFalse((self.state / 'control/maintenance').exists())

    def test_vps_lock_serializes_the_two_repositories(self):
        first_entered, release_first, second_entered = threading.Event(), threading.Event(), threading.Event()
        def transaction(component, *args):
            if component == 'spring':
                first_entered.set()
                if not release_first.wait(5):
                    raise RuntimeError('test lock timeout')
            else:
                second_entered.set()
        with patch.object(deploy, 'deploy', side_effect=transaction), ThreadPoolExecutor(2) as pool:
            first = pool.submit(deploy.worker, ['spring', self.digest, self.commit, '1', '1'])
            self.assertTrue(first_entered.wait(5))
            second = pool.submit(deploy.worker, ['fastapi', self.digest, self.commit, '1', '1'])
            try:
                self.assertFalse(second_entered.wait(0.1))
            finally:
                release_first.set()
            first.result(timeout=5)
            second.result(timeout=5)
            self.assertTrue(second_entered.is_set())


if __name__ == '__main__':
    unittest.main()
