package dev.jobqueue.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param concurrency     number of worker threads in this process
 * @param popTimeout      how long a worker blocks on the queue before re-checking the stop flag;
 *                        must be positive (a zero timeout would block forever and make stop() hang)
 * @param shutdownTimeout how long stop() waits for in-flight jobs before interrupting them
 */
@ConfigurationProperties(prefix = "jobqueue.worker")
public record WorkerProperties(
        @DefaultValue("4") int concurrency,
        @DefaultValue("2s") Duration popTimeout,
        @DefaultValue("30s") Duration shutdownTimeout) {

    public WorkerProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("jobqueue.worker.concurrency must be >= 1");
        }
        if (popTimeout.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("jobqueue.worker.pop-timeout must be positive");
        }
        if (popTimeout.compareTo(shutdownTimeout) >= 0) {
            throw new IllegalArgumentException("jobqueue.worker.pop-timeout must be shorter than shutdown-timeout");
        }
    }
}
