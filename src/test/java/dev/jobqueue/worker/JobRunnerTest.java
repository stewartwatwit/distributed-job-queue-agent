package dev.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.jobqueue.handler.JobHandler;
import dev.jobqueue.handler.JobHandlerRegistry;
import dev.jobqueue.handler.NonRetryableJobException;
import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobService;
import dev.jobqueue.queue.JobQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decision logic of {@link JobRunner}; the real-infrastructure behaviour is covered by WorkerPoolIT. */
class JobRunnerTest {

    private static final String WORKER = "w-test";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID id = UUID.randomUUID();

    private JobService jobs;
    private JobQueue queue;
    private JobHandler handler;
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        jobs = mock(JobService.class);
        queue = mock(JobQueue.class);
        handler = mock(JobHandler.class);
        when(handler.type()).thenReturn("TEST");
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(60), 0, () -> 0.5);
        runner = new JobRunner(jobs, new JobHandlerRegistry(List.of(handler)), queue, policy, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private Job claimedJob(String type, int attempts, int maxAttempts) {
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(id);
        when(job.getType()).thenReturn(type);
        when(job.getPayload()).thenReturn("{\"x\":1}");
        when(job.getAttempts()).thenReturn(attempts);
        when(job.getMaxAttempts()).thenReturn(maxAttempts);
        when(job.hasAttemptsLeft()).thenReturn(attempts < maxAttempts);
        when(jobs.claim(id, WORKER)).thenReturn(true);
        when(jobs.get(id)).thenReturn(job);
        return job;
    }

    @Test
    void unclaimableJobIsDroppedWithoutRunningAnything() throws Exception {
        when(jobs.claim(id, WORKER)).thenReturn(false);

        runner.run(id, WORKER);

        verify(handler, never()).handle(any());
        verifyNoInteractions(queue);
        verify(jobs, never()).get(any());
    }

    @Test
    void successRecordsResult() throws Exception {
        claimedJob("TEST", 1, 3);
        JsonNode result = mapper.readTree("{\"ok\":true}");
        when(handler.handle(any())).thenReturn(result);

        runner.run(id, WORKER);

        verify(jobs).complete(id, WORKER, "{\"ok\":true}");
        verifyNoInteractions(queue);
    }

    @Test
    void retryableFailureWithAttemptsLeftSchedulesBackoff() throws Exception {
        claimedJob("TEST", 2, 3);
        when(handler.handle(any())).thenThrow(new IllegalStateException("boom"));
        when(jobs.scheduleRetry(eq(id), eq(WORKER), anyString())).thenReturn(true);

        runner.run(id, WORKER);

        verify(jobs).scheduleRetry(eq(id), eq(WORKER), contains("IllegalStateException: boom"));
        // second failed attempt -> 4s backoff
        verify(queue).enqueueDelayed(id, NOW.plusSeconds(4));
        verify(jobs, never()).fail(any(), any(), any());
    }

    @Test
    void failureOnLastAttemptIsFinal() throws Exception {
        claimedJob("TEST", 3, 3);
        when(handler.handle(any())).thenThrow(new IllegalStateException("boom"));

        runner.run(id, WORKER);

        verify(jobs).fail(eq(id), eq(WORKER), contains("boom"));
        verify(jobs, never()).scheduleRetry(any(), any(), any());
        verifyNoInteractions(queue);
    }

    @Test
    void nonRetryableFailureIsFinalEvenWithAttemptsLeft() throws Exception {
        claimedJob("TEST", 1, 3);
        when(handler.handle(any())).thenThrow(new NonRetryableJobException("bad input"));

        runner.run(id, WORKER);

        verify(jobs).fail(eq(id), eq(WORKER), contains("bad input"));
        verify(jobs, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void unknownJobTypeFailsPermanently() {
        claimedJob("NO_SUCH_TYPE", 1, 3);

        runner.run(id, WORKER);

        verify(jobs).fail(eq(id), eq(WORKER), contains("Unknown job type"));
    }

    @Test
    void lostLeaseOnCompletionIsTolerated() throws Exception {
        claimedJob("TEST", 1, 3);
        when(handler.handle(any())).thenReturn(mapper.readTree("{}"));
        when(jobs.complete(any(), any(), any())).thenReturn(false);

        runner.run(id, WORKER); // must not throw
    }

    @Test
    void lostLeaseOnFailureDoesNotScheduleRetry() throws Exception {
        claimedJob("TEST", 1, 3);
        when(handler.handle(any())).thenThrow(new IllegalStateException("boom"));
        when(jobs.scheduleRetry(any(), any(), any())).thenReturn(false);

        runner.run(id, WORKER);

        verifyNoInteractions(queue);
    }

    @Test
    void redisFailureWhileSchedulingRetryDoesNotEscape() throws Exception {
        claimedJob("TEST", 1, 3);
        when(handler.handle(any())).thenThrow(new IllegalStateException("boom"));
        when(jobs.scheduleRetry(any(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("redis down")).when(queue).enqueueDelayed(any(), any());

        runner.run(id, WORKER); // must not throw: the sweeper recovers the QUEUED row
    }

    @Test
    void interruptionIsRecordedAsRetryableFailureAndDoesNotLeaveInterruptFlagSet() throws Exception {
        claimedJob("TEST", 1, 3);
        when(handler.handle(any())).thenThrow(new InterruptedException("stop"));
        when(jobs.scheduleRetry(any(), any(), any())).thenReturn(true);

        runner.run(id, WORKER);

        verify(jobs).scheduleRetry(eq(id), eq(WORKER), contains("InterruptedException"));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void handlerErrorIsRecordedInsteadOfStrandingTheJob() throws Exception {
        claimedJob("TEST", 3, 3);
        when(handler.handle(any())).thenThrow(new StackOverflowError());

        runner.run(id, WORKER);

        verify(jobs).fail(eq(id), eq(WORKER), contains("StackOverflowError"));
    }

    @Test
    void fatalJvmErrorsAreRethrown() throws Exception {
        claimedJob("TEST", 1, 3);
        when(handler.handle(any())).thenThrow(new OutOfMemoryError());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.run(id, WORKER))
                .isInstanceOf(OutOfMemoryError.class);
        verify(jobs, never()).fail(any(), any(), any());
    }
}
