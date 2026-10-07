package dev.jobqueue.job;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every state transition is a single conditional UPDATE whose WHERE clause encodes the legal
 * source state (and, once a job is claimed, the owning worker). The returned row count tells the
 * caller whether it won: 1 = transition applied, 0 = the job was not in the expected state (a
 * duplicate message, a lost race, or a lease that was taken away). This is the project's single
 * concurrency gate; no explicit locks are needed.
 *
 * "now()" is the database clock so lease logic is immune to clock skew between workers.
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByStatus(JobStatus status, Pageable pageable);

    /** PENDING -> QUEUED, once the id is confirmed to be in Redis. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE jobs SET status = 'QUEUED', queued_at = now(), updated_at = now()
            WHERE id = :id AND status = 'PENDING'
            """)
    int markQueued(@Param("id") UUID id);

    /**
     * QUEUED -> PROCESSING. Counts the attempt and takes a lease owned by {@code workerId}. Refuses a
     * job that has used all its attempts, so a stray duplicate message can never run attempt max+1.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE jobs SET status = 'PROCESSING', attempts = attempts + 1, locked_by = :workerId,
                lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                started_at = now(), updated_at = now()
            WHERE id = :id AND status = 'QUEUED' AND attempts < max_attempts
            """)
    int claim(@Param("id") UUID id, @Param("workerId") String workerId,
              @Param("leaseSeconds") double leaseSeconds);

    /** PROCESSING -> COMPLETED, only for the worker that still holds the job. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE jobs SET status = 'COMPLETED', result = CAST(:result AS jsonb), last_error = NULL,
                locked_by = NULL, lease_expires_at = NULL, finished_at = now(), updated_at = now()
            WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId
            """)
    int complete(@Param("id") UUID id, @Param("workerId") String workerId,
                 @Param("result") String resultJson);

    /** PROCESSING -> QUEUED for another attempt, only for the worker that still holds the job. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE jobs SET status = 'QUEUED', last_error = :error, locked_by = NULL,
                lease_expires_at = NULL, queued_at = now(), updated_at = now()
            WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId
            """)
    int scheduleRetry(@Param("id") UUID id, @Param("workerId") String workerId,
                      @Param("error") String error);

    /** PROCESSING -> FAILED (terminal), only for the worker that still holds the job. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE jobs SET status = 'FAILED', last_error = :error, locked_by = NULL,
                lease_expires_at = NULL, finished_at = now(), updated_at = now()
            WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId
            """)
    int fail(@Param("id") UUID id, @Param("workerId") String workerId,
             @Param("error") String error);
}
