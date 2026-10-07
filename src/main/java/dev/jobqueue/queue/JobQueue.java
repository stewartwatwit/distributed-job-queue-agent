package dev.jobqueue.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Dispatch channel for job ids. Delivery is at-least-once: an id may be delivered more than once
 * (re-enqueued by the sweeper, for example) or lost (Redis restart), so consumers must treat a
 * popped id as a hint and let PostgreSQL decide whether the job may really run.
 */
public interface JobQueue {

    /** Appends the id to the ready queue (FIFO). */
    void enqueue(UUID jobId);

    /** Schedules the id to become ready at {@code dueAt}; see {@link #moveDueJobs}. */
    void enqueueDelayed(UUID jobId, Instant dueAt);

    /**
     * Blocks up to {@code timeout} for the oldest ready id. Each id is handed to exactly one
     * caller. Empty if nothing arrived in time.
     */
    Optional<UUID> pop(Duration timeout);

    /**
     * Atomically moves up to {@code limit} delayed ids whose time has come to the ready queue.
     * Safe to call from many instances at once; an id is never moved twice.
     *
     * @return number of ids moved
     */
    int moveDueJobs(Instant now, int limit);

    long readySize();

    long delayedSize();
}
