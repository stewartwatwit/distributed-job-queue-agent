package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Redis is a disposable hint queue: wiping it must cost latency, never jobs. The sweeper's stale
 * threshold is shortened to one second so the recovery is observable within a test.
 */
@Tag("concurrency")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.minimum-idle=2",
        "jobqueue.sweeper.enabled=true",
        "jobqueue.sweeper.delayed-interval=50ms",
        "jobqueue.sweeper.stale-interval=200ms",
        "jobqueue.sweeper.stale-after=1s",
        "jobqueue.sweeper.lease-interval=200ms"})
@Import({TestContainersConfig.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisDataLossIT extends ConcurrencyTestBase {

    private void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private void assertEachRanExactlyOnce(List<String> keys) {
        for (String key : keys) {
            assertThat(ConcurrencyHandlers.started(key)).as("executions of %s", key).isEqualTo(1);
        }
        Integer wrongAttempts = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status <> 'COMPLETED' OR attempts <> 1", Integer.class);
        assertThat(wrongAttempts).isZero();
    }

    /**
     * Every id is wiped before any worker exists. All 100 rows sit QUEUED with no message; the stale
     * pass must re-enqueue them and the workers must then finish each exactly once.
     */
    @Test
    void jobsWhoseMessagesWereWipedBeforeAnyWorkerRanStillComplete() {
        List<String> keys = submitWork("wiped", 100, 1);
        assertThat(queue.readySize()).isEqualTo(100);

        flushRedis();
        assertThat(queue.readySize()).isZero();
        startPool(4);

        awaitAllTerminal(100);
        awaitRedisDrained();
        assertEachRanExactlyOnce(keys);
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * Redis is wiped while workers are mid-drain and submissions are still arriving. Jobs already
     * claimed finish normally, the rest lose their messages and need the sweeper; none may be lost
     * and none may run twice (the re-enqueue and the surviving messages overlap).
     */
    @Test
    void flushWhileWorkersDrainLosesNothing() {
        startPool(4);
        List<String> keys = submitWork("flush", 100, 20);
        await().atMost(Duration.ofSeconds(30)).until(() -> countWithStatus("COMPLETED") >= 5);

        flushRedis();
        keys.addAll(submitWork("flush-late", 50, 20));

        awaitAllTerminal(150);
        awaitRedisDrained();
        assertEachRanExactlyOnce(keys);
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * Retries wait in the delayed set. Dropping the set loses the message but not the QUEUED row, so
     * the stale pass must still bring the job back and let it finish on its second attempt.
     */
    @Test
    void jobsWaitingInBackoffSurviveLosingTheDelayedSet() {
        int count = 10;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = jobs.submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload("backoff-" + i, 0), 3).getId();
            jobs.markQueued(id);
            // Attempt 1 failed on some worker; its retry is parked in the delayed set 10 minutes out.
            assertThat(jobs.claim(id, "failing-worker")).isTrue();
            assertThat(jobs.scheduleRetry(id, "failing-worker", "boom")).isTrue();
            queue.enqueueDelayed(id, Instant.now().plus(Duration.ofMinutes(10)));
            ids.add(id);
        }
        assertThat(queue.delayedSize()).isEqualTo(count);

        flushRedis();
        startPool(4);

        awaitAllTerminal(count);
        for (int i = 0; i < count; i++) {
            assertThat(jobs.get(ids.get(i)).getAttempts()).isEqualTo(2);
            assertThat(ConcurrencyHandlers.started("backoff-" + i)).isEqualTo(1);
        }
        awaitRedisDrained();
        JobInvariants.assertQuiescent(jdbc, queue);
    }
}
