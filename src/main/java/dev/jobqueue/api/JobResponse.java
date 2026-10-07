package dev.jobqueue.api;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobStatus;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record JobResponse(
        UUID id,
        String type,
        JobStatus status,
        int attempts,
        int maxAttempts,
        JsonNode payload,
        JsonNode result,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt) {

    static JobResponse from(Job job, JsonMapper mapper) {
        return new JobResponse(job.getId(), job.getType(), job.getStatus(), job.getAttempts(),
                job.getMaxAttempts(), mapper.readTree(job.getPayload()),
                job.getResult() == null ? null : mapper.readTree(job.getResult()),
                job.getLastError(), job.getCreatedAt(), job.getUpdatedAt(), job.getStartedAt(),
                job.getFinishedAt());
    }
}
