#!/usr/bin/python3
"""Root-owned deployment entrypoint. No repository code is executed on the host.

Usage: talentengine-deploy spring|fastapi sha256:DIGEST COMMIT RUN_NUMBER RUN_ATTEMPT
Run via sudo; execution continues in a systemd service if the SSH connection drops.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path('/opt/talentengine')
STATE = ROOT / 'deploy'
REPOSITORIES = {'spring': 'talent-engine-spring-boot', 'fastapi': 'talent-engine-fast-api'}
SERVICES = {'spring': ['spring1', 'spring2'], 'fastapi': ['fastapi']}
DRAIN_TIMEOUT = 600
LOCK_TIMEOUT = 900


def log(message):
    print(message, flush=True)


def atomic(path, content):
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as file:
        name = file.name
        file.write(content)
        file.flush()
        os.fsync(file.fileno())
    os.replace(name, path)


def save_json(path, value):
    atomic(path, json.dumps(value, sort_keys=True) + '\n')


def command(*args, timeout=300):
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        # Container logs/config and command arguments may contain secrets. Keep errors bounded.
        raise RuntimeError(f'{args[0]} failed (exit {result.returncode}); inspect service logs on the VPS.')
    return result.stdout.strip()


def compose(*args, timeout=300):
    return command('/usr/bin/docker', 'compose', '--project-directory', str(ROOT),
                   '--env-file', str(ROOT / '.env'), '-f', str(ROOT / 'compose.yaml'),
                   *args, timeout=timeout)


def load_images():
    values = {}
    for line in (ROOT / '.env').read_text().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key not in ('SPRING_IMAGE', 'FASTAPI_IMAGE'):
            raise RuntimeError('Migrate .env to SPRING_IMAGE and FASTAPI_IMAGE before enabling CI/CD.')
        if not re.fullmatch(r'[a-z0-9][a-z0-9._/:@-]+', value):
            raise RuntimeError('Invalid image reference in .env.')
        values[key] = value
    if set(values) != {'SPRING_IMAGE', 'FASTAPI_IMAGE'}:
        raise RuntimeError('Both runtime image references are required.')
    return values


def write_images(images):
    atomic(ROOT / '.env', ''.join(f'{key}={images[key]}\n' for key in ('SPRING_IMAGE', 'FASTAPI_IMAGE')))


def control_token():
    for line in (ROOT / 'secrets/deploy.env').read_text().splitlines():
        if line.startswith('DEPLOY_CONTROL_TOKEN='):
            token = line.split('=', 1)[1]
            if re.fullmatch(r'[a-f0-9]{64}', token):
                return token
    raise RuntimeError('secrets/deploy.env must contain a 64-character hexadecimal DEPLOY_CONTROL_TOKEN.')


def status(port, token):
    request = urllib.request.Request(f'http://127.0.0.1:{port}/internal/deploy/status',
                                     headers={'X-Deploy-Token': token})
    with urllib.request.urlopen(request, timeout=5) as response:
        value = json.load(response)
    if (value.get('protocol') != 1 or type(value.get('draining')) is not bool
            or any(type(value.get(key)) is not int or value[key] < 0
                   for key in ('activeRequests', 'pendingMatchings'))):
        raise RuntimeError('Unsupported deployment status; bootstrap both Spring instances first.')
    return value


def stopped(port):
    service = 'spring1' if port == 3000 else 'spring2'
    container = compose('ps', '--all', '-q', service)
    return not container or command('/usr/bin/docker', 'inspect', '--format',
                                    '{{.State.Running}}', container) == 'false'


def wait_drained(token, allow_stopped=False):
    deadline = time.monotonic() + DRAIN_TIMEOUT
    while True:
        values = []
        for port in (3000, 3001):
            try:
                values.append(status(port, token))
            except Exception:
                if not allow_stopped or not stopped(port):
                    raise
                log(f'Spring on {port} is already stopped; recovering its previous image.')
        if all(value['draining'] and value['activeRequests'] == 0
               and value['pendingMatchings'] == 0 for value in values):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('Drain timed out; no containers were restarted.')
        time.sleep(2)


def health(token, draining):
    expected = load_images()
    for service in ('spring1', 'spring2', 'fastapi'):
        container = compose('ps', '-q', service)
        if not container or '\n' in container:
            raise RuntimeError(f'{service} has no unique running container.')
        actual = command('/usr/bin/docker', 'inspect', '--format', '{{.State.Health.Status}}', container)
        if actual != 'healthy':
            raise RuntimeError(f'{service} is not healthy.')
        image = command('/usr/bin/docker', 'inspect', '--format', '{{.Config.Image}}', container)
        if image != expected['FASTAPI_IMAGE' if service == 'fastapi' else 'SPRING_IMAGE']:
            raise RuntimeError(f'{service} is not using the expected image.')
    for port in (3000, 3001):
        if status(port, token)['draining'] != draining:
            raise RuntimeError('Spring maintenance state does not match the host flag.')
    for service in ('spring1', 'spring2'):
        compose('exec', '-T', service, 'curl', '-fsS', '--max-time', '8', 'http://fastapi:8000/')
    # Check Nginx/TLS locally, independent of Cloudflare propagation and external DNS.
    command('/usr/bin/curl', '-fsS', '--max-time', '15', '--resolve',
            'api.talentengine.com.br:443:127.0.0.1', 'https://api.talentengine.com.br/healthz')


def replace(component):
    compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait',
            '--wait-timeout', '240', *SERVICES[component], timeout=400)


def validate_args(args):
    if len(args) != 5:
        raise ValueError('Expected: spring|fastapi sha256:DIGEST COMMIT RUN_NUMBER RUN_ATTEMPT')
    component, digest, commit, number, attempt = args
    if (component not in REPOSITORIES or not re.fullmatch(r'sha256:[a-f0-9]{64}', digest)
            or not re.fullmatch(r'[a-f0-9]{40}', commit)
            or not re.fullmatch(r'[1-9][0-9]{0,11}', number)
            or not re.fullmatch(r'[1-9][0-9]{0,5}', attempt)):
        raise ValueError('Invalid deployment arguments.')
    return component, digest, commit, int(number), int(attempt)


def deploy(component, digest, commit, number, attempt):
    flag = STATE / 'control/maintenance'
    pending = STATE / 'pending.json'
    orders_path = STATE / 'orders.json'
    if pending.exists() or flag.exists():
        raise RuntimeError('Unfinished deployment/maintenance exists. Follow the recovery procedure before retrying.')
    orders = json.loads(orders_path.read_text()) if orders_path.exists() else {}
    if number <= orders.get(component, 0):
        log('Skipped: this run or a newer run has already been deployed.')
        return
    previous = load_images()
    token = control_token()
    compose('config', '--quiet')
    health(token, False)
    image = f'ghcr.io/natanf0/{REPOSITORIES[component]}@{digest}'
    if shutil.disk_usage(ROOT).free < 2 * 1024**3:
        raise RuntimeError('At least 2 GiB free disk space is required before pulling the image.')
    log(f'Pulling {component} commit {commit}.')
    command('/usr/bin/docker', 'pull', image, timeout=600)
    labels = json.loads(command('/usr/bin/docker', 'image', 'inspect', '--format',
                                '{{json .Config.Labels}}', image))
    if (labels.get('org.opencontainers.image.revision') != commit
            or labels.get('org.opencontainers.image.source') != f'https://github.com/Natanf0/{REPOSITORIES[component]}'):
        raise RuntimeError('Image provenance labels do not match the requested repository/commit.')
    # Keep exact previous image IDs locally: rollback must never rebuild or require network access.
    for reference in previous.values():
        command('/usr/bin/docker', 'image', 'inspect', reference)
    updated = dict(previous)
    updated[f'{component.upper()}_IMAGE'] = image
    transaction = {'component': component, 'commit': commit, 'run': number, 'attempt': attempt,
                   'previous': previous, 'requested': updated, 'phase': 'draining'}
    save_json(pending, transaction)
    flag.touch(mode=0o644)
    changed = False
    try:
        log('Maintenance enabled; waiting for requests and accepted matchings on both Spring instances.')
        wait_drained(token)
        transaction['phase'] = 'applying'
        save_json(pending, transaction)
        changed = True
        write_images(updated)
        replace(component)
        health(token, True)
    except Exception:
        if changed:
            log('Deployment failed; restoring previous images.')
            transaction['phase'] = 'rolling-back'
            save_json(pending, transaction)
            write_images(previous)
            try:
                replace(component)
                health(token, True)
            except Exception:
                log('ROLLBACK FAILED. Maintenance remains enabled; inspect deploy/pending.json and recover manually.')
                raise
        else:
            # No restart occurred. Accepted requests/jobs can safely continue on the old version.
            log('Deployment aborted before replacing containers; keeping the previous version.')
        transaction['phase'] = 'rolled-back' if changed else 'aborted'
        save_json(STATE / 'last-result.json', transaction)
        flag.unlink()
        pending.unlink()
        raise
    # Finalize before reopening admission. Errors here must never restart newly accepted work.
    transaction['phase'] = 'committed'
    save_json(pending, transaction)
    orders[component] = number
    save_json(orders_path, orders)
    save_json(STATE / 'previous.json', previous)
    save_json(STATE / 'last-result.json', transaction)
    save_json(STATE / 'last-success.json', transaction)
    save_json(STATE / 'history' / f'{component}-{number}-{attempt}.json', transaction)
    flag.unlink()
    pending.unlink()
    log(f'SUCCESS: {component} commit {commit} deployed. Maintenance disabled.')


def recover(rollback=False):
    pending = STATE / 'pending.json'
    flag = STATE / 'control/maintenance'
    if rollback:
        if pending.exists() or flag.exists():
            raise RuntimeError('Use --recover for the unfinished transaction first.')
        transaction = json.loads((STATE / 'last-success.json').read_text())
        # Prevent repeated rollback from undoing an unrelated later manual change.
        if load_images() != transaction['requested']:
            raise RuntimeError('Current images do not match the last successful deployment.')
        save_json(pending, transaction)
    transaction = json.loads(pending.read_text())
    component = transaction['component']
    if component not in SERVICES:
        raise RuntimeError('Invalid recovery component.')
    token = control_token()
    flag.touch(mode=0o644)
    wait_drained(token, allow_stopped=True)
    write_images(transaction['previous'])
    replace(component)
    health(token, True)
    transaction['phase'] = 'recovered'
    save_json(STATE / 'last-result.json', transaction)
    flag.unlink()
    pending.unlink()
    log('Previous images recovered. Maintenance disabled. Run numbers remain monotonic.')


def worker(args):
    recovery = args in (['--recover'], ['--rollback'])
    validated = None if recovery else validate_args(args)
    # Explicit timeout; flock serializes deployment across both repositories.
    with (STATE / 'deploy.lock').open('a') as lock:
        deadline = time.monotonic() + LOCK_TIMEOUT
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Another deployment still holds the VPS lock.')
                time.sleep(2)
        if recovery:
            recover(rollback=args == ['--rollback'])
        else:
            deploy(*validated)


def main():
    if os.geteuid() != 0:
        raise RuntimeError('Run this root-owned entrypoint with sudo.')
    args = sys.argv[1:]
    if args and args[0] == '--worker':
        worker(args[1:])
        return
    if args in (['--recover'], ['--rollback']):
        unit = f'talentengine-deploy-recovery-{time.time_ns()}'
    else:
        component, _, _, number, attempt = validate_args(args)
        unit = f'talentengine-deploy-{component}-{number}-{attempt}'
    # A disconnected/cancelled SSH client does not stop this systemd-owned transaction.
    result = subprocess.run(['/usr/bin/systemd-run', '--quiet', '--wait', '--collect',
                             '--unit', unit, '--property=RuntimeMaxSec=3600',
                             '/usr/bin/python3', str(Path(__file__).resolve()), '--worker', *args])
    subprocess.run(['/usr/bin/journalctl', '--unit', unit, '--no-pager', '-n', '80', '-o', 'cat'])
    if result.returncode:
        raise RuntimeError(f'Deployment failed. Inspect journalctl -u {unit}.')


if __name__ == '__main__':
    # Root does not inherit caller-supplied Docker/Compose/Python paths or credentials.
    os.environ.clear()
    os.environ.update(PATH='/usr/sbin:/usr/bin:/sbin:/bin', HOME='/root', LANG='C.UTF-8')
    os.umask(0o077)
    try:
        main()
    except Exception as error:
        log(f'ERROR: {type(error).__name__}: {error}')
        sys.exit(1)
