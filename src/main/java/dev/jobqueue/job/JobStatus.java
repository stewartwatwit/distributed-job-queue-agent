package dev.jobqueue.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a job.
 *
 * <pre>
 * PENDING -> QUEUED -> PROCESSING -> COMPLETED
 *               ^           |
 *               +-- retry --+---> FAILED
 * </pre>
 *
 * PENDING means "persisted but not yet confirmed in Redis". The rules here document and unit-test
 * the lifecycle; the actual enforcement under concurrency is done by the conditional UPDATEs in
 * {@link JobRepository}.
 */
public enum JobStatus {
    PENDING,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED;

    private Set<JobStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(QUEUED);
            case QUEUED -> EnumSet.of(PROCESSING);
            case PROCESSING -> EnumSet.of(COMPLETED, QUEUED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canTransitionTo(JobStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
