package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.recovery.Sweeper;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Races directly on the conditional UPDATEs (claim / complete / fail / retry / lease reclaim) with
 * no background actors, so every interleaving is provoked by the test itself.
 */
@Tag("concurrency")
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=40", "spring.datasource.hikari.minimum-idle=2"})
@Import({TestContainersConfig.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClaimFencingIT extends ConcurrencyTestBase {

    private static final int RACERS = 24;

    private UUID newQueuedJob(int maxAttempts) {
        UUID id = jobs.submit(ConcurrencyHandlers.TYPE, ConcurrencyHandlers.payload("k", 0), maxAttempts).getId();
        assertThat(jobs.markQueued(id)).isTrue();
        return id;
    }

    private void expireLease(UUID id) {
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id);
    }

    /**
     * 24 threads line up on a barrier and claim the same QUEUED job in the same instant. The
     * conditional UPDATE must admit exactly one; the losers' later outcomes must be fenced off.
     */
    @Test
    void exactlyOneOfManyRacingClaimersWins() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(RACERS);
        try {
            for (int round = 0; round < 10; round++) {
                UUID id = newQueuedJob(3);
                CyclicBarrier barrier = new CyclicBarrier(RACERS);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < RACERS; i++) {
                    String worker = "racer-" + i;
                    results.add(threads.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return jobs.claim(id, worker);
                    }));
                }

                String winner = null;
                int wins = 0;
                for (int i = 0; i < RACERS; i++) {
                    if (results.get(i).get(30, TimeUnit.SECONDS)) {
                        wins++;
                        winner = "racer-" + i;
                    }
                }

                assertThat(wins).as("claim winners in round %d", round).isEqualTo(1);
                Job claimed = jobs.get(id);
                assertThat(claimed.getStatus()).isEqualTo(JobStatus.PROCESSING);
                assertThat(claimed.getAttempts()).isEqualTo(1);
                assertThat(claimed.getLockedBy()).isEqualTo(winner);

                for (int i = 0; i < RACERS; i++) {
                    String worker = "racer-" + i;
                    if (!worker.equals(winner)) {
                        assertThat(jobs.complete(id, worker, "{\"loser\":true}")).as("complete by %s", worker).isFalse();
                        assertThat(jobs.fail(id, worker, "loser")).isFalse();
                        assertThat(jobs.scheduleRetry(id, worker, "loser")).isFalse();
                    }
                }
                assertThat(jobs.get(id).getStatus()).isEqualTo(JobStatus.PROCESSING);
                assertThat(jobs.complete(id, winner, "{\"winner\":true}")).isTrue();
                assertThat(jobs.get(id).getResult()).contains("winner");
            }
        } finally {
            threads.shutdownNow();
        }
        JobInvariants.assertRowInvariants(jdbc);
    }

    /** The same worker finishing the same job from many threads at once must succeed exactly once. */
    @Test
    void concurrentCompletesOfOneClaimApplyOnce() throws Exception {
        UUID id = newQueuedJob(3);
        assertThat(jobs.claim(id, "w")).isTrue();
        ExecutorService threads = Executors.newFixedThreadPool(RACERS);
        try {
            CyclicBarrier barrier = new CyclicBarrier(RACERS);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                results.add(threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return jobs.complete(id, "w", "{\"done\":true}");
                }));
            }
            int applied = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    applied++;
                }
            }
            assertThat(applied).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }
        assertThat(jobs.get(id).getStatus()).isEqualTo(JobStatus.COMPLETED);
        JobInvariants.assertRowInvariants(jdbc);
    }

    /**
     * Worker A stalls past its lease (GC pause, network partition). The sweeper takes the job away,
     * worker B completes it, and only then does A wake up and try to report. None of A's outcomes
     * may touch the row, and the final state must be B's result.
     */
    @Test
    void zombieWorkerCannotOverwriteTheNewOwnersResult() {
        UUID id = newQueuedJob(3);
        assertThat(jobs.claim(id, "worker-A")).isTrue();
        expireLease(id);

        Sweeper sweeper = newSweeper(Duration.ofMinutes(5));
        assertThat(sweeper.reclaimExpiredLeases()).isEqualTo(1);
        assertThat(jobs.get(id).getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(jobs.get(id).getLockedBy()).isNull();

        assertThat(jobs.claim(id, "worker-B")).isTrue();
        assertThat(jobs.get(id).getAttempts()).isEqualTo(2);

        // A wakes up while B still holds the job.
        assertThat(jobs.complete(id, "worker-A", "{\"by\":\"A\"}")).isFalse();
        assertThat(jobs.fail(id, "worker-A", "A says failed")).isFalse();
        assertThat(jobs.scheduleRetry(id, "worker-A", "A says retry")).isFalse();
        assertThat(jobs.get(id).getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(jobs.get(id).getLockedBy()).isEqualTo("worker-B");

        assertThat(jobs.complete(id, "worker-B", "{\"by\":\"B\"}")).isTrue();

        // ...and again after B finished.
        assertThat(jobs.complete(id, "worker-A", "{\"by\":\"A\"}")).isFalse();
        assertThat(jobs.fail(id, "worker-A", "A says failed")).isFalse();
        assertThat(jobs.scheduleRetry(id, "worker-A", "A says retry")).isFalse();

        Job done = jobs.get(id);
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getResult()).contains("\"by\"").contains("B").doesNotContain("A");
        assertThat(done.getAttempts()).isEqualTo(2);
        assertThat(done.getLastError()).isNull();
        queue.moveDueJobs(clock.instant().plus(Duration.ofHours(1)), 100);
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
        JobInvariants.assertRowInvariants(jdbc);
    }

    /**
     * A's late {@code complete} and the sweeper's lease reclaim hit the same expired row at the same
     * instant. Whichever lands first wins and the other must observe it: either A completes and the
     * sweeper recovers nothing, or the sweeper takes the job and A's complete is fenced. A
     * half-and-half outcome (COMPLETED but re-queued, or QUEUED with A's result) is the bug.
     */
    @Test
    void lateCompleteRacingLeaseReclaimHasOneWinner() throws Exception {
        Sweeper sweeper = newSweeper(Duration.ofMinutes(5));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        int completedByA = 0;
        int reclaimed = 0;
        try {
            for (int round = 0; round < 40; round++) {
                UUID id = newQueuedJob(3);
                assertThat(jobs.claim(id, "A")).isTrue();
                expireLease(id);
                redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Boolean> complete = threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return jobs.complete(id, "A", "{\"by\":\"A\"}");
                });
                Future<Integer> reclaim = threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return sweeper.reclaimExpiredLeases();
                });
                boolean aWon = complete.get(30, TimeUnit.SECONDS);
                int recovered = reclaim.get(30, TimeUnit.SECONDS);

                Job job = jobs.get(id);
                if (aWon) {
                    completedByA++;
                    assertThat(recovered).as("round %d: reclaim must not touch a completed job", round).isZero();
                    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
                    assertThat(job.getResult()).contains("A");
                    assertThat(queue.delayedSize()).isZero();
                } else {
                    reclaimed++;
                    assertThat(recovered).as("round %d", round).isEqualTo(1);
                    assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
                    assertThat(job.getResult()).isNull();
                    assertThat(job.getLockedBy()).isNull();
                    assertThat(job.getLastError()).startsWith("Lease expired");
                    assertThat(queue.delayedSize()).isEqualTo(1);
                    // A's retry after losing must stay fenced.
                    assertThat(jobs.complete(id, "A", "{\"by\":\"A\"}")).isFalse();
                }
                JobInvariants.assertRowInvariants(jdbc);
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(completedByA + reclaimed).isEqualTo(40);
    }
}
