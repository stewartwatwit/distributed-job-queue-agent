package dev.jobqueue.job;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job persistence operations. Each method is one short transaction; nothing here holds a
 * transaction open while a job handler runs. Methods returning boolean report whether the
 * conditional UPDATE applied (see {@link JobRepository}).
 */
@Service
public class JobService {

    static final int MAX_ERROR_LENGTH = 2000;

    private final JobRepository jobs;
    private final Clock clock;
    private final int defaultMaxAttempts;
    private final Duration lease;

    public JobService(JobRepository jobs, Clock clock,
                      @Value("${jobqueue.job.default-max-attempts:3}") int defaultMaxAttempts,
                      @Value("${jobqueue.job.lease:5m}") Duration lease) {
        this.jobs = jobs;
        this.clock = clock;
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.lease = lease;
    }

    /** Persists a new job in PENDING. {@code maxAttempts} may be null to use the default. */
    @Transactional
    public Job submit(String type, String payloadJson, Integer maxAttempts) {
        int attempts = maxAttempts != null ? maxAttempts : defaultMaxAttempts;
        return jobs.save(Job.create(type, payloadJson, attempts, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Job> list(JobStatus status, Pageable pageable) {
        return status == null ? jobs.findAll(pageable) : jobs.findByStatus(status, pageable);
    }

    @Transactional
    public boolean markQueued(UUID id) {
        return jobs.markQueued(id) == 1;
    }

    @Transactional
    public boolean claim(UUID id, String workerId) {
        return jobs.claim(id, workerId, lease.toMillis() / 1000.0) == 1;
    }

    @Transactional
    public boolean complete(UUID id, String workerId, String resultJson) {
        return jobs.complete(id, workerId, resultJson) == 1;
    }

    @Transactional
    public boolean scheduleRetry(UUID id, String workerId, String error) {
        return jobs.scheduleRetry(id, workerId, truncate(error)) == 1;
    }

    @Transactional
    public boolean fail(UUID id, String workerId, String error) {
        return jobs.fail(id, workerId, truncate(error)) == 1;
    }

    static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
