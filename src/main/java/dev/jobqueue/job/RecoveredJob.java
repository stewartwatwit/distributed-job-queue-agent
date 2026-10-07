package dev.jobqueue.job;

import java.util.UUID;

/** A job taken back from a worker whose lease expired: its new status and attempts used so far. */
public interface RecoveredJob {

    UUID getId();

    JobStatus getStatus();

    int getAttempts();
}
