package br.com.careermatcher.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingAsyncConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MatchingAsyncConfiguration.class);

    @Test void defaultsToOneWorkerAndTwentyQueuedTasks() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var executor = application.getBean("matchingExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(20);
        });
    }

    @Test void acceptsConfiguredLimits() {
        context.withPropertyValues("matching.async.workers=2", "matching.async.queue-capacity=5").run(application -> {
            assertThat(application).hasNotFailed();
            var executor = application.getBean("matchingExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getQueueCapacity()).isEqualTo(5);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching.async.workers=0", "matching.async.workers=-1",
            "matching.async.queue-capacity=0", "matching.async.queue-capacity=-1"})
    void rejectsInvalidLimits(String property) {
        context.withPropertyValues(property).run(application -> assertThat(application).hasFailed());
    }
}
