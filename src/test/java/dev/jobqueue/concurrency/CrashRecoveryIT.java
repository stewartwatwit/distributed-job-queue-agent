package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Workers that die. A "dead worker" is simulated by claiming a job and never reporting back; the
 * test then backdates the lease the way time passing would. The scheduled sweeper (short
 * intervals) and a real worker pool recover the rest on their own.
 */
@Tag("concurrency")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.minimum-idle=2",
        "jobqueue.sweeper.enabled=true",
        "jobqueue.sweeper.delayed-interval=50ms",
        "jobqueue.sweeper.lease-interval=100ms",
        "jobqueue.sweeper.stale-interval=60s",
        "jobqueue.retry.base-delay=50ms",
        "jobqueue.retry.max-delay=100ms",
        "jobqueue.retry.jitter=0"})
@Import({TestContainersConfig.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CrashRecoveryIT extends ConcurrencyTestBase {

    private UUID submitAndClaimAsDeadWorker(String key, int maxAttempts) {
        UUID id = jobs.submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload(key, 0), maxAttempts).getId();
        jobs.markQueued(id);
        assertThat(jobs.claim(id, "dead-worker")).isTrue();
        return id;
    }

    private void expireLease(UUID id) {
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id);
    }

    /**
     * Twenty workers die mid-job. The sweeper must retry every one (consuming the lost attempt) and
     * the live pool must complete them; the dead attempt never reached the handler, so each handler
     * body runs exactly once.
     */
    @Test
    void jobsOfDeadWorkersAreRetriedAndComplete() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(submitAndClaimAsDeadWorker("crash-" + i, 3));
        }
        ids.forEach(this::expireLease);
        startPool(4);

        awaitAllTerminal(20);
        awaitRedisDrained();

        for (int i = 0; i < 20; i++) {
            Job job = jobs.get(ids.get(i));
            assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.getAttempts()).as("crashed attempt + successful attempt").isEqualTo(2);
            assertThat(ConcurrencyHandlers.started("crash-" + i)).isEqualTo(1);
        }
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * A job whose worker dies on every attempt. After max_attempts crashes it must be FAILED with a
     * lease-expired reason, never run attempt max+1, and stray messages for it afterwards (the
     * delayed ids the earlier recoveries scheduled) must be dropped by live workers.
     */
    @Test
    void repeatedCrashesExhaustAttemptsAndEndFailed() {
        int maxAttempts = 3;
        UUID id = jobs.submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload("doomed", 0), maxAttempts).getId();
        jobs.markQueued(id);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            int expected = attempt;
            await().atMost(Duration.ofSeconds(30)).until(() -> jobs.claim(id, "dead-" + expected));
            assertThat(jobs.get(id).getAttempts()).isEqualTo(attempt);
            expireLease(id);
            await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(id).getStatus() != JobStatus.PROCESSING);
        }

        Job failed = jobs.get(id);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(maxAttempts);
        assertThat(failed.getLastError()).startsWith("Lease expired");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(jobs.claim(id, "dead-extra")).as("attempt max+1 must be refused").isFalse();

        // Live workers now meet the leftover messages for the FAILED job and must drop them.
        queue.enqueue(id);
        queue.enqueue(id);
        startPool(4);
        awaitRedisDrained();

        assertThat(ConcurrencyHandlers.started("doomed")).isZero();
        Job after = jobs.get(id);
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(maxAttempts);
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * A job with max_attempts = 1 whose only attempt crashes: the lease reclaim fails the job
     * rather than re-queueing it, so the handler never runs.
     */
    @Test
    void crashOnFinalAttemptFailsInsteadOfRequeueing() {
        UUID id = submitAndClaimAsDeadWorker("last-chance", 1);
        expireLease(id);

        await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(id).getStatus() == JobStatus.FAILED);
        startPool(2);
        awaitRedisDrained();

        Job job = jobs.get(id);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("Lease expired");
        assertThat(ConcurrencyHandlers.started("last-chance")).isZero();
        JobInvariants.assertQuiescent(jdbc, queue);
    }
}
