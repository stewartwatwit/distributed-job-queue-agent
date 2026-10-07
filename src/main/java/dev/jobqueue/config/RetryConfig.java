package dev.jobqueue.config;

import dev.jobqueue.worker.RetryPolicy;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RetryConfig {

    @Bean
    RetryPolicy retryPolicy(@Value("${jobqueue.retry.base-delay:2s}") Duration baseDelay,
                            @Value("${jobqueue.retry.max-delay:60s}") Duration maxDelay,
                            @Value("${jobqueue.retry.jitter:0.2}") double jitter) {
        return new RetryPolicy(baseDelay, maxDelay, jitter, () -> ThreadLocalRandom.current().nextDouble());
    }
}
