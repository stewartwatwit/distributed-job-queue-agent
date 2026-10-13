package dev.jobqueue.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.support.TestContainersConfig;
import dev.jobqueue.worker.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** The sweep passes against real PostgreSQL and Redis, invoked directly rather than by schedule. */
@SpringBootTest(properties = {"jobqueue.worker.enabled=false", "jobqueue.sweeper.enabled=false"})
@Import(TestContainersConfig.class)
class SweeperIT {

    private static final Duration STALE = Duration.ofMinutes(5);
    private static final String PAYLOAD = "{\"text\":\"hi\"}";

    @Autowired
    JobService service;

    @Autowired
    JobQueue queue;

    @Autowired
    RetryPolicy retryPolicy;

    @Autowired
    Clock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    Sweeper sweeper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
        sweeper = new Sweeper(service, queue, retryPolicy, clock, STALE, 100);
    }

    private UUID pending() {
        return service.submit("ECHO", PAYLOAD, null).getId();
    }

    private UUID queued() {
        UUID id = pending();
        service.markQueued(id);
        return id;
    }

    private UUID processing(int maxAttempts) {
        UUID id = service.submit("ECHO", PAYLOAD, maxAttempts).getId();
        service.markQueued(id);
        assertThat(service.claim(id, "dead-worker")).isTrue();
        return id;
    }

    private void backdateUpdatedAt(UUID id, Duration age) {
        jdbc.update("UPDATE jobs SET updated_at = now() - make_interval(secs => ?) WHERE id = ?",
                age.toMillis() / 1000.0, id);
    }

    private void expireLease(UUID id) {
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id);
    }

    private List<String> ready() {
        List<String> ids = redis.opsForList().range("jobqueue:ready", 0, -1);
        return ids == null ? List.of() : ids;
    }

    private Set<String> delayed() {
        Set<String> ids = redis.opsForZSet().range("jobqueue:delayed", 0, -1);
        return ids == null ? Set.of() : ids;
    }

    @Test
    void stalePendingAndStaleQueuedAreReEnqueuedOnceAndStalenessIsReset() {
        UUID stalePending = pending();
        UUID staleQueued = queued();
        backdateUpdatedAt(stalePending, Duration.ofMinutes(10));
        backdateUpdatedAt(staleQueued, Duration.ofMinutes(10));
        Instant before = service.get(stalePending).getUpdatedAt();

        assertThat(sweeper.requeueStale(STALE)).isEqualTo(2);

        assertThat(ready()).containsExactlyInAnyOrder(stalePending.toString(), staleQueued.toString());
        Job recovered = service.get(stalePending);
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(recovered.getQueuedAt()).isNotNull();
        assertThat(recovered.getUpdatedAt()).isAfter(before);

        assertThat(sweeper.requeueStale(STALE)).isZero();
        assertThat(ready()).hasSize(2);
    }

    @Test
    void freshTerminalAndProcessingJobsAreUntouched() {
        UUID fresh = queued();
        UUID completed = processing(3);
        service.complete(completed, "dead-worker", "{}");
        UUID failed = processing(1);
        service.fail(failed, "dead-worker", "boom");
        UUID running = processing(3);
        for (UUID id : List.of(fresh, completed, failed, running)) {
            backdateUpdatedAt(id, id.equals(fresh) ? Duration.ofSeconds(30) : Duration.ofHours(1));
        }

        assertThat(sweeper.requeueStale(STALE)).isZero();
        assertThat(sweeper.reclaimExpiredLeases()).isZero();

        assertThat(ready()).isEmpty();
        assertThat(delayed()).isEmpty();
        assertThat(service.get(completed).getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(service.get(failed).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(service.get(running).getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void expiredLeaseWithAttemptsLeftGoesBackToQueuedAndIsDelayed() {
        UUID id = processing(3);
        expireLease(id);

        assertThat(sweeper.reclaimExpiredLeases()).isEqualTo(1);

        Job job = service.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getQueuedAt()).isNotNull();
        assertThat(job.getLastError()).isEqualTo("Lease expired: worker did not finish (attempt 1)");
        assertThat(delayed()).containsExactly(id.toString());
        assertThat(ready()).isEmpty();
    }

    @Test
    void expiredLeaseOnFinalAttemptFailsTheJob() {
        UUID id = processing(1);
        expireLease(id);

        assertThat(sweeper.reclaimExpiredLeases()).isEqualTo(1);

        Job job = service.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLastError()).isEqualTo("Lease expired: worker did not finish (attempt 1)");
        assertThat(delayed()).isEmpty();
        assertThat(ready()).isEmpty();
    }

    @Test
    void jobCompletedByItsOwnerBeforeExpiryIsNotTouched() {
        UUID id = processing(3);
        assertThat(service.complete(id, "dead-worker", "{\"ok\":true}")).isTrue();
        expireLease(id);

        assertThat(sweeper.reclaimExpiredLeases()).isZero();

        assertThat(service.get(id).getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(delayed()).isEmpty();
    }

    @Test
    void ownerCannotCompleteAfterSweeperTookTheJob() {
        UUID id = processing(3);
        expireLease(id);
        sweeper.reclaimExpiredLeases();

        assertThat(service.complete(id, "dead-worker", "{}")).isFalse();
    }

    @Test
    void dueDelayedIdsAreMovedToTheReadyQueue() {
        UUID due = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        queue.enqueueDelayed(due, Instant.now().minusSeconds(1));
        queue.enqueueDelayed(later, Instant.now().plus(Duration.ofHours(1)));

        assertThat(sweeper.moveDueJobs()).isEqualTo(1);

        assertThat(ready()).containsExactly(due.toString());
        assertThat(delayed()).containsExactly(later.toString());
    }

    @Test
    void recoveredLeaseBecomesReadyOnceItsBackoffElapses() {
        UUID id = processing(3);
        expireLease(id);
        sweeper.reclaimExpiredLeases();
        assertThat(sweeper.moveDueJobs()).isZero();

        redis.opsForZSet().add("jobqueue:delayed", id.toString(), Instant.now().minusSeconds(1).toEpochMilli());

        assertThat(sweeper.moveDueJobs()).isEqualTo(1);
        assertThat(ready()).containsExactly(id.toString());
        assertThat(service.claim(id, "worker-2")).isTrue();
        assertThat(service.get(id).getAttempts()).isEqualTo(2);
    }

    @Test
    @Tag("concurrency")
    void concurrentSweepersHandleEachStrandedRowExactlyOnce() throws Exception {
        int stale = 120;
        int expired = 120;
        Set<UUID> staleIds = new HashSet<>();
        Set<UUID> expiredIds = new HashSet<>();
        for (int i = 0; i < stale; i++) {
            UUID id = i % 2 == 0 ? pending() : queued();
            backdateUpdatedAt(id, Duration.ofMinutes(10));
            staleIds.add(id);
        }
        for (int i = 0; i < expired; i++) {
            UUID id = processing(3);
            expireLease(id);
            expiredIds.add(id);
        }

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<int[]>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Sweeper s = new Sweeper(service, queue, retryPolicy, clock, STALE, 7);
            results.add(pool.submit(() -> {
                start.await();
                return new int[] {s.requeueStale(STALE), s.reclaimExpiredLeases()};
            }));
        }
        start.countDown();
        int staleHandled = 0;
        int expiredHandled = 0;
        for (Future<int[]> f : results) {
            int[] r = f.get(60, TimeUnit.SECONDS);
            staleHandled += r[0];
            expiredHandled += r[1];
        }
        pool.shutdown();

        assertThat(staleHandled).isEqualTo(stale);
        assertThat(expiredHandled).isEqualTo(expired);
        List<String> readyIds = ready();
        assertThat(readyIds).hasSize(stale).doesNotHaveDuplicates();
        assertThat(readyIds).containsExactlyInAnyOrderElementsOf(staleIds.stream().map(UUID::toString).toList());
        assertThat(delayed()).containsExactlyInAnyOrderElementsOf(expiredIds.stream().map(UUID::toString).toList());
    }
}
