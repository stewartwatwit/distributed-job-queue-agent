package dev.jobqueue.worker;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter for retried jobs.
 *
 * <pre>delay(attempt) = min(cap, base * 2^(attempt-1) * (1 +/- jitterRatio))</pre>
 *
 * Jitter spreads out retries so many jobs that failed together (a downstream outage, say) don't
 * all come back at the same instant. The random source is injectable so the maths is testable.
 * Whether a job may be retried at all (attempts left, retryable error) is decided by the caller.
 */
public class RetryPolicy {

    private static final int MAX_EXPONENT = 30;

    private final Duration base;
    private final Duration cap;
    private final double jitterRatio;
    private final DoubleSupplier random;

    /** @param random supplies values in [0, 1) */
    public RetryPolicy(Duration base, Duration cap, double jitterRatio, DoubleSupplier random) {
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must be >= base");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio must be within [0, 1]");
        }
        this.base = base;
        this.cap = cap;
        this.jitterRatio = jitterRatio;
        this.random = random;
    }

    /**
     * Delay before the next attempt.
     *
     * @param failedAttempts number of attempts that have already failed (1 after the first failure)
     */
    public Duration delayAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be >= 1");
        }
        int exponent = Math.min(failedAttempts - 1, MAX_EXPONENT);
        double rawMillis = (double) base.toMillis() * (1L << exponent);
        double jitter = 1 + jitterRatio * (2 * random.getAsDouble() - 1);
        long millis = (long) Math.min(rawMillis * jitter, (double) cap.toMillis());
        return Duration.ofMillis(millis);
    }
}
