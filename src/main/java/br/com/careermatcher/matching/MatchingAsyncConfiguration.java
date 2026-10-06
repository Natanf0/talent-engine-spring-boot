package br.com.careermatcher.matching;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class MatchingAsyncConfiguration {
    @Bean
    public ThreadPoolTaskExecutor matchingExecutor(
            @Value("${matching.async.workers:1}") int workers,
            @Value("${matching.async.queue-capacity:20}") int queueCapacity) {
        if (workers < 1 || queueCapacity < 1)
            throw new IllegalArgumentException("matching.async.workers e matching.async.queue-capacity devem ser positivos.");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("matching-");
        // Never execute rejected work on the HTTP request thread.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setStrictEarlyShutdown(true);
        return executor;
    }
}
