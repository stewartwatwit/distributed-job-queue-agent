package dev.jobqueue.worker;

import dev.jobqueue.handler.JobHandler;
import dev.jobqueue.handler.JobHandlerRegistry;
import dev.jobqueue.handler.NonRetryableJobException;
import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobService;
import dev.jobqueue.queue.JobQueue;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one job id popped from the queue: claim, execute, record the outcome.
 *
 * The popped id is only a hint. The claim is a conditional UPDATE, so if another worker already
 * took the job (duplicate message) or it is no longer QUEUED, this worker silently drops the
 * message. No database transaction is open while the handler runs. Outcomes are fenced on the
 * worker id: if the lease was lost in the meantime (the sweeper gave the job away) the outcome is
 * discarded rather than overwriting newer state.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobService jobs;
    private final JobHandlerRegistry handlers;
    private final JobQueue queue;
    private final RetryPolicy retryPolicy;
    private final JsonMapper mapper;
    private final Clock clock;

    public JobRunner(JobService jobs, JobHandlerRegistry handlers, JobQueue queue,
                     RetryPolicy retryPolicy, JsonMapper mapper, Clock clock) {
        this.jobs = jobs;
        this.handlers = handlers;
        this.queue = queue;
        this.retryPolicy = retryPolicy;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void run(UUID jobId, String workerId) {
        if (!jobs.claim(jobId, workerId)) {
            log.debug("Job {} not claimable by {} (duplicate or stale message), dropping", jobId, workerId);
            return;
        }
        Job job = jobs.get(jobId);

        String resultJson;
        try {
            JobHandler handler = handlers.get(job.getType());
            JsonNode payload = mapper.readTree(job.getPayload());
            handler.validate(payload);
            resultJson = mapper.writeValueAsString(handler.handle(payload));
        } catch (InterruptedException e) {
            // Shutdown interrupted us mid-job. Leave it PROCESSING: the sweeper recovers it once the lease expires.
            Thread.currentThread().interrupt();
            log.warn("Job {} interrupted on {}; it will be recovered after its lease expires", jobId, workerId);
            return;
        } catch (Exception e) {
            recordFailure(job, workerId, e);
            return;
        }

        if (jobs.complete(jobId, workerId, resultJson)) {
            log.info("Job {} ({}) completed on attempt {}", jobId, job.getType(), job.getAttempts());
        } else {
            log.warn("Job {} finished on {} but its lease was lost; result discarded", jobId, workerId);
        }
    }

    private void recordFailure(Job job, String workerId, Exception error) {
        String message = error.getClass().getName() + ": " + error.getMessage();
        boolean retryable = !(error instanceof NonRetryableJobException) && job.hasAttemptsLeft();

        if (!retryable) {
            if (jobs.fail(job.getId(), workerId, message)) {
                log.warn("Job {} ({}) failed permanently after {} attempt(s): {}",
                        job.getId(), job.getType(), job.getAttempts(), message);
            } else {
                log.warn("Job {} failed on {} but its lease was lost; outcome discarded", job.getId(), workerId);
            }
            return;
        }

        if (!jobs.scheduleRetry(job.getId(), workerId, message)) {
            log.warn("Job {} failed on {} but its lease was lost; outcome discarded", job.getId(), workerId);
            return;
        }
        Duration delay = retryPolicy.delayAfter(job.getAttempts());
        log.info("Job {} ({}) failed attempt {}/{}, retrying in {}: {}",
                job.getId(), job.getType(), job.getAttempts(), job.getMaxAttempts(), delay, message);
        try {
            queue.enqueueDelayed(job.getId(), clock.instant().plus(delay));
        } catch (RuntimeException e) {
            // The row is already QUEUED in PostgreSQL; the sweeper re-enqueues stale QUEUED jobs.
            log.error("Could not schedule retry of job {} in Redis; the sweeper will recover it", job.getId(), e);
        }
    }
}
