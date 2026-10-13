package dev.jobqueue.recovery;

import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.job.RecoveredJob;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.worker.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs the gaps between PostgreSQL (source of truth) and Redis (disposable hint queue).
 *
 * <ul>
 *   <li>stale PENDING/QUEUED rows: the Redis message was never sent or got lost, so re-enqueue;</li>
 *   <li>expired PROCESSING leases: the worker died, so retry (with backoff) or fail the job;</li>
 *   <li>delayed ids that are due: move them from the delayed set to the ready queue.</li>
 * </ul>
 *
 * Re-enqueueing may duplicate a message that is merely slow; that is harmless because the claim
 * UPDATE only succeeds for a QUEUED row. Every task swallows its exceptions so an outage (Redis
 * down, say) cannot kill the scheduler; the next run simply tries again.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class Sweeper {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    /** Bounds one run so a huge backlog cannot monopolise a scheduler thread. */
    static final int MAX_BATCHES_PER_RUN = 10;

    private final JobService jobs;
    private final JobQueue queue;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final Duration staleAfter;
    private final int batchSize;

    public Sweeper(JobService jobs, JobQueue queue, RetryPolicy retryPolicy, Clock clock,
                   @Value("${jobqueue.sweeper.stale-after:5m}") Duration staleAfter,
                   @Value("${jobqueue.sweeper.batch-size:100}") int batchSize) {
        this.jobs = jobs;
        this.queue = queue;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${jobqueue.sweeper.delayed-interval:1s}")
    void scheduledMoveDue() {
        moveDueJobs();
    }

    @Scheduled(fixedDelayString = "${jobqueue.sweeper.stale-interval:30s}")
    void scheduledRequeueStale() {
        requeueStale(staleAfter);
    }

    @Scheduled(fixedDelayString = "${jobqueue.sweeper.lease-interval:10s}")
    void scheduledReclaimExpiredLeases() {
        reclaimExpiredLeases();
    }

    /** Moves due delayed ids to the ready queue; returns how many were moved. */
    public int moveDueJobs() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                int moved = queue.moveDueJobs(clock.instant(), batchSize);
                total += moved;
                if (moved == 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Moving due delayed jobs failed", e);
        }
        return total;
    }

    /**
     * Re-enqueues PENDING/QUEUED jobs not touched for {@code staleAfter}. A job that is genuinely
     * waiting in a long backlog also looks stale and gets a duplicate message; that costs one
     * no-op claim, never a double execution.
     *
     * @return number of jobs re-stamped (an id whose Redis push failed still counts; the next
     *         stale pass recovers it)
     */
    public int requeueStale(Duration staleAfter) {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                List<UUID> ids = jobs.requeueStale(staleAfter, batchSize);
                for (UUID id : ids) {
                    enqueueQuietly(id);
                }
                total += ids.size();
                if (ids.size() < batchSize) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Re-enqueueing stale jobs failed", e);
        }
        if (total > 0) {
            log.info("Re-enqueued {} stale job(s)", total);
        }
        return total;
    }

    /**
     * Retries or fails jobs whose worker lease has expired. Retried jobs go through the delayed
     * set so a job that keeps killing its worker is backed off like any other failure.
     *
     * @return number of jobs recovered
     */
    public int reclaimExpiredLeases() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                List<RecoveredJob> recovered = jobs.reclaimExpiredLeases(batchSize);
                for (RecoveredJob job : recovered) {
                    if (job.getStatus() == JobStatus.QUEUED) {
                        scheduleRetryQuietly(job);
                    } else {
                        log.warn("Job {} failed: lease expired on final attempt {}", job.getId(), job.getAttempts());
                    }
                }
                total += recovered.size();
                if (recovered.size() < batchSize) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Reclaiming expired leases failed", e);
        }
        if (total > 0) {
            log.info("Recovered {} job(s) with expired leases", total);
        }
        return total;
    }

    private void enqueueQuietly(UUID id) {
        try {
            queue.enqueue(id);
        } catch (RuntimeException e) {
            log.warn("Could not enqueue stale job {}; a later pass will retry", id, e);
        }
    }

    private void scheduleRetryQuietly(RecoveredJob job) {
        try {
            queue.enqueueDelayed(job.getId(), clock.instant().plus(retryPolicy.delayAfter(job.getAttempts())));
        } catch (RuntimeException e) {
            log.warn("Could not schedule retry of job {}; the stale pass will recover it", job.getId(), e);
        }
    }
}
