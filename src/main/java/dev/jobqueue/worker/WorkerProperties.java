package dev.jobqueue.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param concurrency     number of worker threads in this process
 * @param popTimeout      how long a worker blocks on the queue before re-checking the stop flag
 * @param shutdownTimeout how long stop() waits for in-flight jobs before interrupting them
 */
@ConfigurationProperties(prefix = "jobqueue.worker")
public record WorkerProperties(
        @DefaultValue("4") int concurrency,
        @DefaultValue("2s") Duration popTimeout,
        @DefaultValue("30s") Duration shutdownTimeout) {
}
