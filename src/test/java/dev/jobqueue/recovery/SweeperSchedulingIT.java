package dev.jobqueue.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Proves the @Scheduled wiring: with the sweeper enabled and short intervals, nobody calls it by
 * hand. The context is discarded afterwards so its live sweeper cannot touch other tests' rows.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=false",
        "jobqueue.sweeper.enabled=true",
        "jobqueue.sweeper.delayed-interval=100ms",
        "jobqueue.sweeper.stale-interval=100ms",
        "jobqueue.sweeper.lease-interval=100ms",
        "jobqueue.sweeper.stale-after=5m"})
@Import(TestContainersConfig.class)
class SweeperSchedulingIT {

    @Autowired
    JobService service;

    @Autowired
    JobQueue queue;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
    }

    @Test
    void scheduledTasksRecoverStrandedJobsAndMoveDelayedIds() {
        UUID stale = service.submit("ECHO", "{}", null).getId();
        jdbc.update("UPDATE jobs SET updated_at = now() - interval '10 minutes' WHERE id = ?", stale);
        UUID delayed = UUID.randomUUID();
        queue.enqueueDelayed(delayed, Instant.now().minusSeconds(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(service.get(stale).getStatus()).isEqualTo(JobStatus.QUEUED);
            assertThat(redis.opsForList().range("jobqueue:ready", 0, -1))
                    .containsExactlyInAnyOrder(stale.toString(), delayed.toString());
            assertThat(queue.delayedSize()).isZero();
        });
    }

    @Test
    void scheduledLeaseCheckRetriesDeadWorkersJob() {
        UUID id = service.submit("ECHO", "{}", 3).getId();
        service.markQueued(id);
        service.claim(id, "dead-worker");
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(service.get(id).getStatus()).isEqualTo(JobStatus.QUEUED));
    }
}
