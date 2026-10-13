package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.recovery.Sweeper;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The hostile steady state: two worker pools, the scheduled sweeper, and two more hand-driven
 * sweepers hammering the same tables, all with aggressive intervals. The stale threshold (200ms)
 * is far below the time a job waits in the backlog, so the sweepers keep re-enqueueing jobs that
 * already have messages, flooding the workers with duplicates. Nothing crashes and no lease can
 * expire (the default lease is five minutes), so every job must still run exactly once.
 */
@Tag("concurrency")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=40",
        "spring.datasource.hikari.minimum-idle=2",
        "jobqueue.sweeper.enabled=true",
        "jobqueue.sweeper.delayed-interval=20ms",
        "jobqueue.sweeper.stale-interval=50ms",
        "jobqueue.sweeper.stale-after=200ms",
        "jobqueue.sweeper.lease-interval=50ms",
        "jobqueue.sweeper.batch-size=25"})
@Import({TestContainersConfig.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkersAndSweepersTogetherIT extends ConcurrencyTestBase {

    @Test
    void duplicateFloodFromConcurrentSweepersNeverCausesDoubleExecution() throws Exception {
        int total = 300;
        startPool(4);
        startPool(4);

        AtomicBoolean done = new AtomicBoolean();
        ExecutorService sweeperThreads = Executors.newFixedThreadPool(2);
        List<Future<?>> sweeping = new ArrayList<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        for (int i = 0; i < 2; i++) {
            Sweeper sweeper = newSweeper(Duration.ofMillis(200));
            sweeping.add(sweeperThreads.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                while (!done.get()) {
                    sweeper.requeueStale(Duration.ofMillis(200));
                    sweeper.reclaimExpiredLeases();
                    sweeper.moveDueJobs();
                    Thread.sleep(10);
                }
                return null;
            }));
        }

        List<String> keys;
        try {
            keys = submitWork("together", total, 15);
            awaitAllTerminal(total);
            JobInvariants.assertRowInvariants(jdbc);
        } finally {
            done.set(true);
            for (Future<?> future : sweeping) {
                future.get(30, TimeUnit.SECONDS);
            }
            sweeperThreads.shutdownNow();
        }
        awaitRedisDrained();

        for (String key : keys) {
            assertThat(ConcurrencyHandlers.started(key)).as("executions of %s", key).isEqualTo(1);
            assertThat(ConcurrencyHandlers.finished(key)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status <> 'COMPLETED' OR attempts <> 1",
                Integer.class)).isZero();
        JobInvariants.assertQuiescent(jdbc, queue);
    }
}
