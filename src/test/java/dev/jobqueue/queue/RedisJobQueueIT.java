package dev.jobqueue.queue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Exercises {@link RedisJobQueue} against a real Redis. */
@SpringBootTest
@Import(TestContainersConfig.class)
class RedisJobQueueIT {

    private static final Duration SHORT = Duration.ofSeconds(1);

    @Autowired
    JobQueue queue;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void cleanKeys() {
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
    }

    @Test
    void popsInFifoOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        queue.enqueue(a);
        queue.enqueue(b);
        queue.enqueue(c);

        assertThat(queue.readySize()).isEqualTo(3);
        assertThat(queue.pop(SHORT)).contains(a);
        assertThat(queue.pop(SHORT)).contains(b);
        assertThat(queue.pop(SHORT)).contains(c);
        assertThat(queue.readySize()).isZero();
    }

    @Test
    void popReturnsEmptyAfterTimeoutWhenQueueIsEmpty() {
        long start = System.nanoTime();

        assertThat(queue.pop(SHORT)).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(800));
    }

    @Test
    void blockedPopWakesUpWhenJobIsEnqueued() throws Exception {
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> consumer = pool.submit(() -> queue.pop(Duration.ofSeconds(5)));
            Thread.sleep(300); // let the consumer block (timing only affects how long the test waits)
            queue.enqueue(id);

            assertThat(consumer.get(3, TimeUnit.SECONDS)).isEqualTo(java.util.Optional.of(id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void delayedJobIsNotMovedBeforeItIsDue() {
        Instant now = Instant.now();
        queue.enqueueDelayed(UUID.randomUUID(), now.plusSeconds(60));

        assertThat(queue.moveDueJobs(now, 100)).isZero();
        assertThat(queue.delayedSize()).isEqualTo(1);
        assertThat(queue.readySize()).isZero();
    }

    @Test
    void dueJobsAreMovedToReadyOldestFirst() {
        Instant now = Instant.now();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        queue.enqueueDelayed(second, now.minusSeconds(5));
        queue.enqueueDelayed(first, now.minusSeconds(10));
        queue.enqueueDelayed(later, now.plusSeconds(60));

        assertThat(queue.moveDueJobs(now, 100)).isEqualTo(2);

        assertThat(queue.delayedSize()).isEqualTo(1);
        assertThat(queue.pop(SHORT)).contains(first);
        assertThat(queue.pop(SHORT)).contains(second);
        assertThat(queue.pop(SHORT)).isEmpty();
    }

    @Test
    void moveRespectsLimit() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            queue.enqueueDelayed(UUID.randomUUID(), now.minusSeconds(1));
        }

        assertThat(queue.moveDueJobs(now, 3)).isEqualTo(3);
        assertThat(queue.delayedSize()).isEqualTo(2);
        assertThat(queue.readySize()).isEqualTo(3);
    }

    @Test
    @Tag("concurrency")
    void concurrentConsumersEachReceiveEveryIdExactlyOnce() throws Exception {
        int jobs = 200;
        int consumers = 8;
        Set<UUID> expected = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < jobs; i++) {
            UUID id = UUID.randomUUID();
            expected.add(id);
            queue.enqueue(id);
        }

        List<UUID> received = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < consumers; i++) {
                futures.add(pool.submit(() -> {
                    while (queue.pop(SHORT).map(received::add).isPresent()) {
                        // keep draining until the queue stays empty for the timeout
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(received).hasSize(jobs).doesNotHaveDuplicates();
        assertThat(received).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @Tag("concurrency")
    void concurrentMoversNeverMoveTheSameIdTwice() throws Exception {
        int jobs = 300;
        int movers = 6;
        Instant now = Instant.now();
        for (int i = 0; i < jobs; i++) {
            queue.enqueueDelayed(UUID.randomUUID(), now.minusSeconds(1));
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalMoved = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(movers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < movers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int moved;
                    do {
                        moved = queue.moveDueJobs(now, 7);
                        totalMoved.addAndGet(moved);
                    } while (moved > 0);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(totalMoved.get()).isEqualTo(jobs);
        assertThat(queue.readySize()).isEqualTo(jobs);
        assertThat(queue.delayedSize()).isZero();
    }
}
