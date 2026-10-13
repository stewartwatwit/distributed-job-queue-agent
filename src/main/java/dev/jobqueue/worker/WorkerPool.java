package dev.jobqueue.worker;

import dev.jobqueue.queue.JobQueue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * A fixed set of worker threads, each looping on a blocking pop from the queue.
 *
 * Thread-per-worker with a blocking pop keeps the model easy to reason about: a thread handles one
 * job at a time, so concurrency equals the thread count. Workers share no mutable state besides
 * the {@code running} flag; all coordination between workers happens in PostgreSQL (the claim).
 *
 * Shutdown: {@link #stop()} clears the flag, so idle workers exit within one pop timeout, and
 * waits for in-flight jobs. Jobs still running after the timeout are interrupted and left
 * PROCESSING for the sweeper to recover.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);
    private static final long ERROR_BACKOFF_MILLIS = 1000;

    private final JobQueue queue;
    private final JobRunner runner;
    private final WorkerProperties properties;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /** Written by start/stop, read by every worker thread. */
    private volatile boolean running;
    private ExecutorService executor;

    public WorkerPool(JobQueue queue, JobRunner runner, WorkerProperties properties) {
        this.queue = queue;
        this.runner = runner;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newFixedThreadPool(properties.concurrency(), task -> {
            Thread thread = new Thread(task, "job-worker-" + instanceId + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        for (int i = 1; i <= properties.concurrency(); i++) {
            String workerId = "w-" + instanceId + "-" + i;
            executor.execute(() -> workLoop(workerId));
        }
        log.info("Started {} worker thread(s) as instance {}", properties.concurrency(), instanceId);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Workers still busy after {}; interrupting", properties.shutdownTimeout());
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Workers stopped (instance {})", instanceId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void workLoop(String workerId) {
        while (running) {
            try {
                queue.pop(properties.popTimeout()).ifPresent(id -> runner.run(id, workerId));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) {
                    // The pool silently loses this worker; make that visible before the thread dies.
                    log.error("Worker {} is dying from a fatal JVM error", workerId, t);
                    throw (VirtualMachineError) t;
                }
                if (!running) {
                    break;
                }
                // A stray interrupt while still running must not poison later pops or the backoff sleep.
                Thread.interrupted();
                // Typically Redis or PostgreSQL being briefly unavailable: back off, keep the worker alive.
                log.error("Worker {} hit an error; continuing", workerId, t);
                pause();
            }
        }
        log.debug("Worker {} exiting", workerId);
    }

    private void pause() {
        try {
            Thread.sleep(ERROR_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
