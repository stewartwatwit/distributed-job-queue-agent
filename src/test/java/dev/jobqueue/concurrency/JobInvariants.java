package dev.jobqueue.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.queue.JobQueue;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Whole-system invariants that must hold at any instant (row invariants) or once the system has
 * gone quiet (quiescent invariants), no matter how workers, sweepers and Redis interleaved.
 */
public final class JobInvariants {

    private JobInvariants() {
    }

    /** Violations expressed as SQL predicates over the jobs table; each must match no row. */
    private static final Map<String, String> ROW_RULES = Map.of(
            "PROCESSING row without an owner or lease",
            "status = 'PROCESSING' AND (locked_by IS NULL OR lease_expires_at IS NULL)",
            "non-PROCESSING row still holding an owner or lease",
            "status <> 'PROCESSING' AND (locked_by IS NOT NULL OR lease_expires_at IS NOT NULL)",
            "attempts beyond max_attempts",
            "attempts > max_attempts",
            "PROCESSING row with no counted attempt",
            "status = 'PROCESSING' AND attempts = 0",
            "terminal row without finished_at",
            "status IN ('COMPLETED', 'FAILED') AND finished_at IS NULL",
            "non-terminal row with finished_at",
            "status IN ('PENDING', 'QUEUED', 'PROCESSING') AND finished_at IS NOT NULL",
            "COMPLETED row without a result",
            "status = 'COMPLETED' AND result IS NULL",
            "FAILED row without last_error",
            "status = 'FAILED' AND last_error IS NULL");

    /** Invariants that hold at every instant, even mid-flight. */
    public static void assertRowInvariants(JdbcTemplate jdbc) {
        ROW_RULES.forEach((rule, predicate) -> {
            List<String> offenders = jdbc.queryForList(
                    "SELECT id::text || ' (' || status || ', attempts=' || attempts || ')' FROM jobs WHERE " + predicate,
                    String.class);
            assertThat(offenders).as("invariant violated: %s", rule).isEmpty();
        });
    }

    /**
     * Invariants for a finished run: the row invariants, every job terminal, and nothing left in
     * Redis (no stray ready or delayed ids).
     */
    public static void assertQuiescent(JdbcTemplate jdbc, JobQueue queue) {
        assertRowInvariants(jdbc);
        List<String> unfinished = jdbc.queryForList(
                "SELECT id::text || ' (' || status || ')' FROM jobs WHERE status NOT IN ('COMPLETED', 'FAILED')",
                String.class);
        assertThat(unfinished).as("jobs that never reached a terminal state").isEmpty();
        assertThat(queue.readySize()).as("ids left in the ready queue").isZero();
        assertThat(queue.delayedSize()).as("ids left in the delayed set").isZero();
    }
}
