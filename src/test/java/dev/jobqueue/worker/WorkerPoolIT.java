package dev.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * End-to-end worker behaviour against real PostgreSQL and Redis. Jobs are enqueued the same way the
 * API will (persist, mark QUEUED, push the id); a background task plays the role of the sweeper's
 * delayed-job mover.
 */
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=4",
        "jobqueue.worker.pop-timeout=500ms",
        "jobqueue.retry.base-delay=50ms",
        "jobqueue.retry.max-delay=200ms",
        "jobqueue.retry.jitter=0"
})
@Import({TestContainersConfig.class, TestJobHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // stop this context's workers before other ITs run
class WorkerPoolIT {

    @Autowired
    JobService jobs;

    @Autowired
    JobQueue queue;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    private ScheduledExecutorService mover;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM jobs");
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
        TestJobHandlers.EXECUTIONS.clear();
        mover = Executors.newSingleThreadScheduledExecutor();
        mover.scheduleWithFixedDelay(() -> queue.moveDueJobs(Instant.now(), 100), 0, 25, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void tearDown() {
        mover.shutdownNow();
    }

    private UUID submit(String type, String payloadJson, Integer maxAttempts) {
        UUID id = jobs.submit(type, payloadJson, maxAttempts).getId();
        jobs.markQueued(id);
        queue.enqueue(id);
        return id;
    }

    private Job awaitTerminal(UUID id) {
        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(id).getStatus().isTerminal());
        return jobs.get(id);
    }

    @Test
    void successfulJobCompletesWithResult() {
        UUID id = submit("CHECKSUM", "{\"text\":\"abc\"}", null);

        Job job = awaitTerminal(id);

        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getResult()).contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLockedBy()).isNull();
    }

    @Test
    void transientFailureIsRetriedUntilItSucceeds() {
        UUID id = submit("TEST_FLAKY", "{\"key\":\"flaky\",\"failFirst\":2}", 5);

        Job job = awaitTerminal(id);

        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(TestJobHandlers.executions("flaky")).isEqualTo(3);
    }

    @Test
    void jobFailsPermanentlyAfterMaxAttempts() {
        UUID id = submit("TEST_FAIL", "{\"key\":\"fail\"}", 3);

        Job job = awaitTerminal(id);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(job.getLastError()).contains("IllegalStateException").contains("always fails");
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(TestJobHandlers.executions("fail")).isEqualTo(3);
    }

    @Test
    void nonRetryableFailureIsNotRetried() {
        UUID id = submit("TEST_FATAL", "{\"key\":\"fatal\"}", 5);

        Job job = awaitTerminal(id);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(TestJobHandlers.executions("fatal")).isEqualTo(1);
    }

    @Test
    void jobWithUnknownTypeFailsWithoutKillingWorkers() {
        UUID bad = submit("NO_SUCH_TYPE", "{}", 3);
        assertThat(awaitTerminal(bad).getStatus()).isEqualTo(JobStatus.FAILED);

        UUID good = submit("ECHO", "{\"hello\":\"world\"}", null);
        assertThat(awaitTerminal(good).getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @Tag("concurrency")
    void duplicateQueueMessagesRunTheJobOnce() {
        UUID id = jobs.submit("TEST_COUNT", "{\"key\":\"dup\"}", null).getId();
        jobs.markQueued(id);
        for (int i = 0; i < 10; i++) {
            queue.enqueue(id);
        }

        awaitTerminal(id);
        await().atMost(Duration.ofSeconds(10)).until(() -> queue.readySize() == 0);

        assertThat(TestJobHandlers.executions("dup")).isEqualTo(1);
        assertThat(jobs.get(id).getAttempts()).isEqualTo(1);
    }

    @Test
    @Tag("concurrency")
    void manyJobsAreEachExecutedExactlyOnce() {
        int count = 60;
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(submit("TEST_COUNT", "{\"key\":\"job-" + i + "\"}", null));
        }

        for (UUID id : ids) {
            assertThat(awaitTerminal(id).getStatus()).isEqualTo(JobStatus.COMPLETED);
        }
        for (int i = 0; i < count; i++) {
            assertThat(TestJobHandlers.executions("job-" + i)).as("job-%d executions", i).isEqualTo(1);
        }
    }
}
