package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.support.TestContainersConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Many workers, one queue, one database: every job must run exactly once. The pools are started by
 * the tests (the context's own pool is disabled) so that several independent instances can share
 * the infrastructure like separate worker processes.
 */
@Tag("concurrency")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=40",
        "spring.datasource.hikari.minimum-idle=2"})
@Import({TestContainersConfig.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExactlyOnceIT extends ConcurrencyTestBase {

    private static final int JOBS = 200;

    /** Asserts every key ran once, completed on attempt 1, and produced a distinct, correct result. */
    private void assertEachJobRanExactlyOnce(List<String> keys) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempts, result->>'key' AS result_key, result->>'execution' AS execution FROM jobs");
        assertThat(rows).hasSize(keys.size());
        Set<Object> resultKeys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
            assertThat(row.get("execution")).isEqualTo("1");
            assertThat(resultKeys.add(row.get("result_key"))).as("duplicate result for %s", row.get("result_key")).isTrue();
        }
        assertThat(resultKeys).containsExactlyInAnyOrderElementsOf(keys);
        for (String key : keys) {
            assertThat(ConcurrencyHandlers.started(key)).as("executions of %s", key).isEqualTo(1);
            assertThat(ConcurrencyHandlers.finished(key)).isEqualTo(1);
        }
    }

    /** One process, eight worker threads, 200 jobs. */
    @Test
    void singlePoolOfEightRunsEveryJobOnce() {
        startPool(8);
        List<String> keys = submitWork("single", JOBS, 2);

        awaitAllTerminal(JOBS);
        awaitRedisDrained();

        assertEachJobRanExactlyOnce(keys);
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * Two worker "processes" (separate pools, separate instance ids, so separate lock owners) start
     * before the jobs arrive and compete for each message while submissions are still streaming in.
     */
    @Test
    void twoIndependentPoolsShareTheQueueWithoutDuplicates() {
        startPool(4);
        startPool(4);
        List<String> keys = submitWork("two", JOBS, 2);

        awaitAllTerminal(JOBS);
        awaitRedisDrained();

        assertEachJobRanExactlyOnce(keys);
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * Submissions arrive from eight threads at once while two pools drain. Exercises the
     * insert -> mark QUEUED -> enqueue ordering under contention: no worker may pop an id whose row
     * is not yet claimable and silently lose it.
     */
    @Test
    void concurrentSubmittersAndTwoPoolsLoseNothing() throws Exception {
        startPool(4);
        startPool(4);
        int submitters = 8;
        int perSubmitter = 25;
        ExecutorService threads = Executors.newFixedThreadPool(submitters);
        List<String> keys = new ArrayList<>();
        try {
            CyclicBarrier barrier = new CyclicBarrier(submitters);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int s = 0; s < submitters; s++) {
                int submitter = s;
                futures.add(threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    List<String> mine = new ArrayList<>();
                    for (int i = 0; i < perSubmitter; i++) {
                        String key = "sub-" + submitter + "-" + i;
                        submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload(key, 1), null);
                        mine.add(key);
                    }
                    return mine;
                }));
            }
            for (Future<List<String>> future : futures) {
                keys.addAll(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }

        awaitAllTerminal(submitters * perSubmitter);
        awaitRedisDrained();

        assertEachJobRanExactlyOnce(keys);
        JobInvariants.assertQuiescent(jdbc, queue);
    }

    /**
     * Redis delivers at least once, so the same id can arrive many times, even while the job is
     * running. Eight threads push one id 25 times each into the ready list while a pool is draining
     * it and the handler is slow enough that many copies pop mid-execution. The claim must let
     * exactly one through.
     */
    @Test
    void sameIdDeliveredHundredsOfTimesRunsOnce() throws Exception {
        UUID id = jobs.submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload("dup", 300), 3).getId();
        jobs.markQueued(id);
        startPool(8);

        int pushers = 8;
        ExecutorService threads = Executors.newFixedThreadPool(pushers);
        try {
            CyclicBarrier barrier = new CyclicBarrier(pushers);
            List<Future<?>> futures = new ArrayList<>();
            for (int p = 0; p < pushers; p++) {
                futures.add(threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < 25; i++) {
                        queue.enqueue(id);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        awaitAllTerminal(1);
        awaitRedisDrained();

        assertThat(ConcurrencyHandlers.started("dup")).isEqualTo(1);
        assertThat(ConcurrencyHandlers.finished("dup")).isEqualTo(1);
        assertThat(jobs.get(id).getAttempts()).isEqualTo(1);
        JobInvariants.assertQuiescent(jdbc, queue);
    }
}
