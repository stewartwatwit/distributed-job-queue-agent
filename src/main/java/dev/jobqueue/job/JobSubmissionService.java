package dev.jobqueue.job;

import dev.jobqueue.handler.InvalidJobPayloadException;
import dev.jobqueue.handler.JobHandlerRegistry;
import dev.jobqueue.queue.JobQueue;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts a new job: validates it, persists it, then hands its id to the queue.
 *
 * <p>Not transactional on purpose. The row must be committed before the id reaches Redis, or a
 * worker could pop an id whose job it cannot see yet.
 */
@Service
public class JobSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(JobSubmissionService.class);

    private final JobHandlerRegistry handlers;
    private final JobService jobs;
    private final JobQueue queue;
    private final JsonMapper mapper;
    private final int maxPayloadBytes;

    public JobSubmissionService(JobHandlerRegistry handlers, JobService jobs, JobQueue queue, JsonMapper mapper,
                                @Value("${jobqueue.job.max-payload-bytes:65536}") int maxPayloadBytes) {
        this.handlers = handlers;
        this.jobs = jobs;
        this.queue = queue;
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /**
     * @throws dev.jobqueue.handler.UnknownJobTypeException if no handler exists for {@code type}
     * @throws dev.jobqueue.handler.InvalidJobPayloadException if the handler rejects the payload
     */
    public Job submit(String type, JsonNode payload, Integer maxAttempts) {
        handlers.get(type).validate(payload);

        // Payloads are stored as JSONB and echoed back by the API, so bound their size.
        String payloadJson = mapper.writeValueAsString(payload);
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new InvalidJobPayloadException("payload exceeds " + maxPayloadBytes + " bytes");
        }

        Job job = jobs.submit(type, payloadJson, maxAttempts);

        // QUEUED must be committed before the id is visible to workers: a worker only claims
        // QUEUED jobs, so popping the id while the row is still PENDING would drop the message.
        jobs.markQueued(job.getId());
        try {
            queue.enqueue(job.getId());
        } catch (RuntimeException e) {
            // The job is durably QUEUED; a sweeper re-enqueues stale QUEUED jobs, so don't fail the client.
            log.warn("Job {} stored but could not be enqueued; it will be re-enqueued later", job.getId(), e);
        }
        return jobs.get(job.getId());
    }
}
