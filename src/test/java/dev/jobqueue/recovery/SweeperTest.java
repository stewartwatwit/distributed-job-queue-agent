package dev.jobqueue.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.job.RecoveredJob;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.worker.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SweeperTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration STALE = Duration.ofMinutes(5);

    private final JobService jobs = mock(JobService.class);
    private final JobQueue queue = mock(JobQueue.class);
    private final RetryPolicy retryPolicy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(60), 0, () -> 0.5);
    private final Sweeper sweeper = new Sweeper(jobs, queue, retryPolicy, Clock.fixed(NOW, ZoneOffset.UTC), STALE, 2);

    private static RecoveredJob recovered(UUID id, JobStatus status, int attempts) {
        RecoveredJob job = mock(RecoveredJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getStatus()).thenReturn(status);
        when(job.getAttempts()).thenReturn(attempts);
        return job;
    }

    @Test
    void requeueStaleEnqueuesEveryReturnedIdAndPagesUntilShortBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(jobs.requeueStale(STALE, 2)).thenReturn(List.of(a, b)).thenReturn(List.of(c));

        assertThat(sweeper.requeueStale(STALE)).isEqualTo(3);

        verify(queue).enqueue(a);
        verify(queue).enqueue(b);
        verify(queue).enqueue(c);
    }

    @Test
    void redisFailureDoesNotAbortTheBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(jobs.requeueStale(STALE, 2)).thenReturn(List.of(a, b)).thenReturn(List.of());
        doThrow(new IllegalStateException("redis down")).when(queue).enqueue(a);

        assertThat(sweeper.requeueStale(STALE)).isEqualTo(2);

        verify(queue).enqueue(b);
    }

    @Test
    void databaseFailureIsSwallowed() {
        when(jobs.requeueStale(STALE, 2)).thenThrow(new IllegalStateException("db down"));
        when(jobs.reclaimExpiredLeases(2)).thenThrow(new IllegalStateException("db down"));
        when(queue.moveDueJobs(any(), anyInt())).thenThrow(new IllegalStateException("redis down"));

        assertThat(sweeper.requeueStale(STALE)).isZero();
        assertThat(sweeper.reclaimExpiredLeases()).isZero();
        assertThat(sweeper.moveDueJobs()).isZero();
    }

    @Test
    void expiredLeaseWithAttemptsLeftIsScheduledWithBackoffAndExhaustedOneIsNot() {
        UUID retry = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        RecoveredJob retried = recovered(retry, JobStatus.QUEUED, 2);
        RecoveredJob exhausted = recovered(dead, JobStatus.FAILED, 3);
        when(jobs.reclaimExpiredLeases(2)).thenReturn(List.of(retried, exhausted)).thenReturn(List.of());

        assertThat(sweeper.reclaimExpiredLeases()).isEqualTo(2);

        verify(queue).enqueueDelayed(retry, NOW.plus(retryPolicy.delayAfter(2)));
        verify(queue, never()).enqueueDelayed(eq(dead), any());
    }

    @Test
    void moveDueJobsLoopsUntilNothingIsLeft() {
        when(queue.moveDueJobs(NOW, 2)).thenReturn(2, 1, 0);

        assertThat(sweeper.moveDueJobs()).isEqualTo(3);
    }

    @Test
    void moveDueJobsIsCappedPerRun() {
        when(queue.moveDueJobs(NOW, 2)).thenReturn(2);

        assertThat(sweeper.moveDueJobs()).isEqualTo(2 * Sweeper.MAX_BATCHES_PER_RUN);
    }
}
