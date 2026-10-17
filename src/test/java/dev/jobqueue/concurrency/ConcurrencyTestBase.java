package dev.jobqueue.concurrency;

import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobSubmissionService;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.recovery.Sweeper;
import dev.jobqueue.worker.JobRunner;
import dev.jobqueue.worker.RetryPolicy;
import dev.jobqueue.worker.TestJobHandlers;
import dev.jobqueue.worker.WorkerPool;
import dev.jobqueue.worker.WorkerProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing for the concurrency suite. The Spring context has the built-in worker pool
 * disabled; tests start their own {@link WorkerPool}s from the context's beans, which lets one test
 * run several independent pools (each with its own instance id) against the same PostgreSQL and
 * Redis, the way several worker processes would. Subclasses must be annotated with
 * {@code @SpringBootTest}, {@code @Import} of the test configurations and
 * {@code @DirtiesContext(AFTER_CLASS)}.
 */
abstract class ConcurrencyTestBase {

    /** Generous upper bound for anything that should converge; tests return as soon as it does. */
    static final Duration PATIENT = Duration.ofSeconds(60);

    @Autowired
    JobService jobs;

    @Autowired
    JobSubmissionService submission;

    @Autowired
    JobQueue queue;

    @Autowired
    JobRunner runner;

    @Autowired
    RetryPolicy retryPolicy;

    @Autowired
    Clock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JsonMapper mapper;

    private final List<WorkerPool> pools = new CopyOnWriteArrayList<>();

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM jobs");
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
        TestJobHandlers.EXECUTIONS.clear();
        ConcurrencyHandlers.reset();
    }

    @AfterEach
    void stopPools() {
        pools.forEach(WorkerPool::stop);
        pools.clear();
    }

    WorkerPool startPool(int concurrency) {
        return startPool(concurrency, Duration.ofSeconds(30));
    }

    /** Starts an independent pool (its own random instance id), as if it were another process. */
    WorkerPool startPool(int concurrency, Duration shutdownTimeout) {
        WorkerPool pool = new WorkerPool(queue, runner,
                new WorkerProperties(concurrency, Duration.ofMillis(200), shutdownTimeout));
        pools.add(pool);
        pool.start();
        return pool;
    }

    /** A sweeper that is driven by hand (or by a test thread) rather than by the scheduler. */
    Sweeper newSweeper(Duration staleAfter) {
        return new Sweeper(jobs, queue, retryPolicy, clock, staleAfter, 100);
    }

    /** Submits through the same path as the REST API: validate, persist, mark QUEUED, enqueue. */
    UUID submit(String type, String payloadJson, Integer maxAttempts) {
        return submission.submit(type, mapper.readTree(payloadJson), maxAttempts).getId();
    }

    /** Submits {@code count} CONC_WORK jobs named {@code prefix-i}; returns their keys in order. */
    List<String> submitWork(String prefix, int count, long millis) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = prefix + "-" + i;
            submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload(key, millis), null);
            keys.add(key);
        }
        return keys;
    }

    int countWithStatus(String status) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = ?", Integer.class, status);
        return count == null ? 0 : count;
    }

    int countNonTerminal() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status NOT IN ('COMPLETED', 'FAILED')", Integer.class);
        return count == null ? 0 : count;
    }

    void awaitAllTerminal(int expectedJobs) {
        await().atMost(PATIENT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            org.assertj.core.api.Assertions.assertThat(countNonTerminal()).as("non-terminal jobs").isZero();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class))
                    .as("total jobs").isEqualTo(expectedJobs);
        });
    }

    /** Waits until Redis holds neither ready nor delayed ids, i.e. every message was consumed. */
    void awaitRedisDrained() {
        await().atMost(PATIENT).pollInterval(Duration.ofMillis(100))
                .until(() -> queue.readySize() == 0 && queue.delayedSize() == 0);
    }
}
