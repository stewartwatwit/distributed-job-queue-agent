package dev.jobqueue.api;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobStatus;
import java.time.Instant;
import java.util.UUID;

public record JobStatusResponse(UUID id, JobStatus status, int attempts, Instant updatedAt) {

    static JobStatusResponse from(Job job) {
        return new JobStatusResponse(job.getId(), job.getStatus(), job.getAttempts(), job.getUpdatedAt());
    }
}
