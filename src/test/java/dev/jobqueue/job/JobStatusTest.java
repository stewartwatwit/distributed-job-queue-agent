package dev.jobqueue.job;

import static dev.jobqueue.job.JobStatus.COMPLETED;
import static dev.jobqueue.job.JobStatus.FAILED;
import static dev.jobqueue.job.JobStatus.PENDING;
import static dev.jobqueue.job.JobStatus.PROCESSING;
import static dev.jobqueue.job.JobStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobStatusTest {

    private static final Map<JobStatus, Set<JobStatus>> LEGAL = Map.of(
            PENDING, Set.of(QUEUED),
            QUEUED, Set.of(PROCESSING),
            PROCESSING, Set.of(COMPLETED, QUEUED, FAILED),
            COMPLETED, Set.of(),
            FAILED, Set.of());

    @Test
    void onlyLegalTransitionsAreAllowed() {
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(LEGAL.get(from).contains(to));
            }
        }
    }

    @Test
    void completedAndFailedAreTerminal() {
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(QUEUED.isTerminal()).isFalse();
        assertThat(PROCESSING.isTerminal()).isFalse();
    }
}
