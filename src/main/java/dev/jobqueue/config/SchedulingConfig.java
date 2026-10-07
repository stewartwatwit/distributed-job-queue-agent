package dev.jobqueue.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is only switched on together with the sweeper, its sole user. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "jobqueue.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
