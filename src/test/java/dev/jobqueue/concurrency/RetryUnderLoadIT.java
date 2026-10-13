package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.support.TestContainersConfig;
import dev.jobqueue.worker.TestJobHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Retries with several workers racing and the sweeper's delayed-job mover active. A retry
 * re-enters the system through the delayed set, so this stresses the fail -> QUEUED -> delayed ->
 * ready -> claim loop from many threads at once. The handler invocation counter is the independent
 * witness: a job must have been executed exactly as often as its attempts column says.
 */
@Tag("concurrency")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.minimum-idle=2",
        "jobqueue.sweeper.enabled=true",
        "jobqueue.sweeper.delayed-interval=30ms",
        "jobqueue.sweeper.stale-interval=60s",
        "jobqueue.sweeper.lease-interval=60s",
        "jobqueue.retry.base-delay=20ms",
        "jobqueue.retry.max-delay=100ms",
        "jobqueue.retry.jitter=0.5"})
@Import({TestContainersConfig.class, TestJobHandlers.class, ConcurrencyHandlers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RetryUnderLoadIT extends ConcurrencyTestBase {

    private static final int MAX_ATTEMPTS = 4;

    private record Expectation(UUID id, String key, int expectedAttempts, JobStatus expectedStatus) {
    }

    @Test
    void flakyAlwaysFailingAndFatalJobsAllEndCorrectlyWithExactAttemptCounts() {
        startPool(4);
        startPool(4);

        List<Expectation> expectations = new ArrayList<>();
        // failFirst 0..5 with 4 attempts: 0..3 succeed on attempt failFirst+1; 4 and 5 exhaust the budget.
        for (int i = 0; i < 72; i++) {
            int failFirst = i % 6;
            String key = "flaky-" + i;
            UUID id = submit("TEST_FLAKY", "{\"key\":\"" + key + "\",\"failFirst\":" + failFirst + "}", MAX_ATTEMPTS);
            boolean succeeds = failFirst < MAX_ATTEMPTS;
            expectations.add(new Expectation(id, key, succeeds ? failFirst + 1 : MAX_ATTEMPTS,
                    succeeds ? JobStatus.COMPLETED : JobStatus.FAILED));
        }
        for (int i = 0; i < 12; i++) {
            String key = "fail-" + i;
            UUID id = submit("TEST_FAIL", "{\"key\":\"" + key + "\"}", 3);
            expectations.add(new Expectation(id, key, 3, JobStatus.FAILED));
        }
        for (int i = 0; i < 12; i++) {
            String key = "fatal-" + i;
            UUID id = submit("TEST_FATAL", "{\"key\":\"" + key + "\"}", 5);
            expectations.add(new Expectation(id, key, 1, JobStatus.FAILED));
        }

        awaitAllTerminal(expectations.size());
        awaitRedisDrained();

        for (Expectation expected : expectations) {
            Job job = jobs.get(expected.id());
            assertThat(job.getStatus()).as(expected.key()).isEqualTo(expected.expectedStatus());
            assertThat(job.getAttempts()).as("attempts of %s", expected.key()).isEqualTo(expected.expectedAttempts());
            assertThat(job.getAttempts()).isLessThanOrEqualTo(job.getMaxAttempts());
            assertThat(TestJobHandlers.executions(expected.key()))
                    .as("handler executions of %s must equal its attempts", expected.key())
                    .isEqualTo(job.getAttempts());
            if (expected.expectedStatus() == JobStatus.FAILED) {
                assertThat(job.getLastError()).isNotBlank();
            }
        }
        JobInvariants.assertQuiescent(jdbc, queue);
    }
}
