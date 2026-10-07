package dev.jobqueue.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent job. The entity is used to insert and read jobs; every state change after creation
 * goes through the conditional UPDATEs in {@link JobRepository} so that concurrent workers cannot
 * overwrite each other. JSON columns are exposed as raw JSON strings; the API layer owns parsing.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private int attempts;

    private int maxAttempts;

    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    private String lockedBy;
    private Instant leaseExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant queuedAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected Job() {
        // for JPA
    }

    public static Job create(String type, String payloadJson, int maxAttempts, Instant now) {
        Job job = new Job();
        job.id = UUID.randomUUID();
        job.type = type;
        job.payload = payloadJson;
        job.status = JobStatus.PENDING;
        job.maxAttempts = maxAttempts;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getLastError() { return lastError; }
    public String getResult() { return result; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public boolean hasAttemptsLeft() {
        return attempts < maxAttempts;
    }
}
