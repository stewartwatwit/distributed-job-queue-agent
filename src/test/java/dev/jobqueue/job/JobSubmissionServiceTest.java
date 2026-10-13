package dev.jobqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.jobqueue.handler.ChecksumHandler;
import dev.jobqueue.handler.EchoHandler;
import dev.jobqueue.handler.InvalidJobPayloadException;
import dev.jobqueue.handler.JobHandlerRegistry;
import dev.jobqueue.handler.UnknownJobTypeException;
import dev.jobqueue.queue.JobQueue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JobSubmissionServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JobService jobs = mock(JobService.class);
    private final JobQueue queue = mock(JobQueue.class);
    private JobSubmissionService service;
    private Job stored;

    @BeforeEach
    void setUp() {
        JobHandlerRegistry registry = new JobHandlerRegistry(
                List.of(new EchoHandler(mapper), new ChecksumHandler(mapper)));
        service = new JobSubmissionService(registry, jobs, queue, mapper);
        stored = Job.create("ECHO", "{\"a\":1}", 3, Instant.parse("2026-01-01T00:00:00Z"));
        when(jobs.submit(anyString(), anyString(), any())).thenReturn(stored);
        when(jobs.get(stored.getId())).thenReturn(stored);
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    @Test
    void persistsSerializedPayloadThenReturnsReReadJob() {
        Job result = service.submit("ECHO", json("{\"a\":1}"), 5);

        verify(jobs).submit("ECHO", "{\"a\":1}", 5);
        assertThat(result).isSameAs(stored);
    }

    @Test
    void marksQueuedBeforeEnqueueing() {
        service.submit("ECHO", json("{}"), null);

        InOrder order = inOrder(jobs, queue);
        order.verify(jobs).submit(eq("ECHO"), anyString(), eq(null));
        order.verify(jobs).markQueued(stored.getId());
        order.verify(queue).enqueue(stored.getId());
    }

    @Test
    void enqueueFailureStillReturnsTheStoredJob() {
        doThrow(new IllegalStateException("redis down")).when(queue).enqueue(stored.getId());

        Job result = service.submit("ECHO", json("{}"), null);

        assertThat(result).isSameAs(stored);
        verify(jobs).markQueued(stored.getId());
    }

    @Test
    void unknownTypeIsRejectedBeforePersisting() {
        assertThatThrownBy(() -> service.submit("NOPE", json("{}"), null))
                .isInstanceOf(UnknownJobTypeException.class);
        verifyNoInteractions(jobs, queue);
    }

    @Test
    void invalidPayloadIsRejectedBeforePersisting() {
        assertThatThrownBy(() -> service.submit("CHECKSUM", json("{}"), null))
                .isInstanceOf(InvalidJobPayloadException.class);
        verifyNoInteractions(jobs, queue);
    }
}
