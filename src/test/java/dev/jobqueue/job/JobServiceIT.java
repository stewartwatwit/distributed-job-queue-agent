package dev.jobqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jobqueue.support.TestContainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs against real PostgreSQL: verifies the Flyway schema, JPA mapping and conditional UPDATEs. */
@SpringBootTest
@Import(TestContainersConfig.class)
class JobServiceIT {

    private static final String PAYLOAD = "{\"text\":\"hello\"}";

    @Autowired
    JobService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanJobs() {
        jdbc.update("DELETE FROM jobs");
    }

    private UUID queuedJob(Integer maxAttempts) {
        UUID id = service.submit("ECHO", PAYLOAD, maxAttempts).getId();
        assertThat(service.markQueued(id)).isTrue();
        return id;
    }

    @Test
    void submitPersistsPendingJobWithDefaults() {
        Job job = service.submit("ECHO", PAYLOAD, null);

        Job loaded = service.get(job.getId());
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(loaded.getType()).isEqualTo("ECHO");
        assertThat(loaded.getPayload()).contains("hello");
        assertThat(loaded.getAttempts()).isZero();
        assertThat(loaded.getMaxAttempts()).isEqualTo(3);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void submitHonoursExplicitMaxAttempts() {
        assertThat(service.submit("ECHO", PAYLOAD, 5).getMaxAttempts()).isEqualTo(5);
    }

    @Test
    void getUnknownJobThrows() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void markQueuedOnlyWorksFromPending() {
        UUID id = service.submit("ECHO", PAYLOAD, null).getId();

        assertThat(service.markQueued(id)).isTrue();
        assertThat(service.markQueued(id)).isFalse();
        assertThat(service.get(id).getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.get(id).getQueuedAt()).isNotNull();
    }

    @Test
    void claimTakesLeaseAndCountsAttempt() {
        UUID id = queuedJob(null);

        assertThat(service.claim(id, "worker-1")).isTrue();

        Job job = service.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLockedBy()).isEqualTo("worker-1");
        assertThat(job.getLeaseExpiresAt()).isAfter(job.getStartedAt());
    }

    @Test
    void secondClaimLoses() {
        UUID id = queuedJob(null);

        assertThat(service.claim(id, "worker-1")).isTrue();
        assertThat(service.claim(id, "worker-2")).isFalse();
        assertThat(service.get(id).getLockedBy()).isEqualTo("worker-1");
        assertThat(service.get(id).getAttempts()).isEqualTo(1);
    }

    @Test
    void cannotClaimJobThatIsNotQueued() {
        UUID pending = service.submit("ECHO", PAYLOAD, null).getId();
        assertThat(service.claim(pending, "worker-1")).isFalse();
    }

    @Test
    void claimRefusesJobThatHasUsedAllItsAttempts() {
        UUID id = queuedJob(1);
        assertThat(service.claim(id, "worker-1")).isTrue();
        // simulate a (buggy or racing) requeue of an exhausted job
        assertThat(service.scheduleRetry(id, "worker-1", "boom")).isTrue();

        assertThat(service.claim(id, "worker-2")).isFalse();
        assertThat(service.get(id).getAttempts()).isEqualTo(1);
    }

    @Test
    void completeRecordsResultAndClearsLease() {
        UUID id = queuedJob(null);
        service.claim(id, "worker-1");

        assertThat(service.complete(id, "worker-1", "{\"ok\":true}")).isTrue();

        Job job = service.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getResult()).contains("true");
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void completionIsFencedByWorker() {
        UUID id = queuedJob(null);
        service.claim(id, "worker-1");

        assertThat(service.complete(id, "worker-2", "{}")).isFalse();
        assertThat(service.get(id).getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void completedJobCannotBeChangedAgain() {
        UUID id = queuedJob(null);
        service.claim(id, "worker-1");
        service.complete(id, "worker-1", "{}");

        assertThat(service.fail(id, "worker-1", "late")).isFalse();
        assertThat(service.scheduleRetry(id, "worker-1", "late")).isFalse();
        assertThat(service.get(id).getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void retryReturnsJobToQueueKeepingErrorAndAttempts() {
        UUID id = queuedJob(null);
        service.claim(id, "worker-1");

        assertThat(service.scheduleRetry(id, "worker-1", "boom")).isTrue();

        Job job = service.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getLastError()).isEqualTo("boom");
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.hasAttemptsLeft()).isTrue();

        assertThat(service.claim(id, "worker-2")).isTrue();
        assertThat(service.get(id).getAttempts()).isEqualTo(2);
    }

    @Test
    void failIsTerminalAndKeepsError() {
        UUID id = queuedJob(1);
        service.claim(id, "worker-1");

        assertThat(service.get(id).hasAttemptsLeft()).isFalse();
        assertThat(service.fail(id, "worker-1", "fatal")).isTrue();

        Job job = service.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).isEqualTo("fatal");
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(service.claim(id, "worker-2")).isFalse();
    }

    @Test
    void longErrorsAreTruncated() {
        UUID id = queuedJob(null);
        service.claim(id, "worker-1");

        service.fail(id, "worker-1", "x".repeat(10_000));

        assertThat(service.get(id).getLastError()).hasSize(JobService.MAX_ERROR_LENGTH);
    }
}
