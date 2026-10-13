package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.JobStatus;
import dev.jobqueue.support.TestContainersConfig;
import dev.jobqueue.worker.WorkerPool;
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
 * Stopping a pool while it is busy. The sweeper's mover is on (it carries retries back to the
 * ready queue) but lease reclaim and stale re-enqueue are effectively off, so nothing except the
 * worker's own shutdown handling can rescue a job.
 */
@Tag("concurrency")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.minimum-idle=2",
        "jobqueue.sweeper.enabled=true",
        "jobqueue.sweeper.delayed-interval=50ms",
        "jobqueue.sweeper.stale-interval=1h",
        "jobqueue.sweeper.lease-interval=1h",
        "jobqueue.retry.base-delay=50ms",
        "jobqueue.retry.max-delay=100ms",
        "jobqueue.retry.jitter=0"})
@Import({TestContainersConfig.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GracefulShutdownUnderLoadIT extends ConcurrencyTestBase {

    /**
     * 60 short jobs, four threads, stop() fired mid-drain with a generous timeout. In-flight jobs
     * must finish (no PROCESSING row, every started handler also finished), unstarted jobs must
     * keep their Redis message, and a fresh pool must complete the remainder with no re-execution.
     */
    @Test
    void stoppingMidDrainFinishesInFlightJobsAndLosesNothing() {
        int total = 60;
        WorkerPool first = startPool(4, Duration.ofSeconds(30));
        List<String> keys = submitWork("drain", total, 100);
        await().atMost(Duration.ofSeconds(30)).until(() -> countWithStatus("PROCESSING") >= 2);

        first.stop();

        assertThat(first.isRunning()).isFalse();
        assertThat(countWithStatus("PROCESSING")).as("jobs left PROCESSING after stop").isZero();
        for (String key : keys) {
            assertThat(ConcurrencyHandlers.started(key)).as("started %s", key)
                    .isEqualTo(ConcurrencyHandlers.finished(key));
        }
        int completed = countWithStatus("COMPLETED");
        int queued = countWithStatus("QUEUED");
        assertThat(completed).isGreaterThanOrEqualTo(1);
        assertThat(completed + queued).as("no job lost or in an odd state").isEqualTo(total);
        assertThat(queue.readySize()).as("every unfinished job still has its message").isEqualTo(queued);
        JobInvariants.assertRowInvariants(jdbc);

        startPool(4);

        awaitAllTerminal(total);
        awaitRedisDrained();
        for (String key : keys) {
            assertThat(ConcurrencyHandlers.started(key)).as("executions of %s", key).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status <> 'COMPLETED' OR attempts <> 1",
                Integer.class)).isZero();
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * The shutdown timeout is shorter than the running jobs, so stop() interrupts them. An
     * interrupted attempt must be recorded as a failure and re-queued (not left PROCESSING with a
     * live-looking lease), and a restarted pool must finish everything. The interrupted jobs only
     * sleep on their first execution so the retry is quick.
     */
    @Test
    void interruptedInFlightJobsAreRequeuedAndFinishAfterRestart() {
        WorkerPool first = startPool(3, Duration.ofMillis(500));
        List<UUID> slow = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slow.add(submit(ConcurrencyHandlers.TYPE,
                    "{\"key\":\"slow-" + i + "\",\"millis\":20000,\"slowFirstOnly\":true}", 3));
        }
        await().atMost(Duration.ofSeconds(30)).until(() ->
                ConcurrencyHandlers.started("slow-0") + ConcurrencyHandlers.started("slow-1")
                        + ConcurrencyHandlers.started("slow-2") == 3);
        List<String> fastKeys = submitWork("fast", 9, 10);

        first.stop();

        assertThat(countWithStatus("PROCESSING")).as("jobs left PROCESSING after forced stop").isZero();
        for (UUID id : slow) {
            var job = jobs.get(id);
            assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
            assertThat(job.getAttempts()).isEqualTo(1);
            assertThat(job.getLastError()).contains("InterruptedException");
        }
        JobInvariants.assertRowInvariants(jdbc);

        startPool(3);

        awaitAllTerminal(12);
        awaitRedisDrained();
        for (int i = 0; i < 3; i++) {
            assertThat(jobs.get(slow.get(i)).getStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(jobs.get(slow.get(i)).getAttempts()).isEqualTo(2);
            assertThat(ConcurrencyHandlers.started("slow-" + i)).isEqualTo(2);
            assertThat(ConcurrencyHandlers.finished("slow-" + i)).as("only the retry finished").isEqualTo(1);
        }
        for (String key : fastKeys) {
            assertThat(ConcurrencyHandlers.started(key)).isEqualTo(1);
        }
        JobInvariants.assertQuiescent(jdbc, queue);
    }
}
