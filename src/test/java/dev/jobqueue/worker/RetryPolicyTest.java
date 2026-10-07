package dev.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(2);
    private static final Duration CAP = Duration.ofSeconds(60);

    /** random = 0.5 means "no jitter" because jitter factor = 1 + ratio * (2 * 0.5 - 1) = 1. */
    private RetryPolicy noJitter() {
        return new RetryPolicy(BASE, CAP, 0.2, () -> 0.5);
    }

    @Test
    void delayDoublesWithEachFailure() {
        RetryPolicy policy = noJitter();

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void delayIsCapped() {
        RetryPolicy policy = noJitter();

        assertThat(policy.delayAfter(6)).isEqualTo(CAP);
        assertThat(policy.delayAfter(10)).isEqualTo(CAP);
        assertThat(policy.delayAfter(1000)).isEqualTo(CAP);
    }

    @Test
    void jitterStaysWithinConfiguredBand() {
        Duration low = new RetryPolicy(BASE, CAP, 0.2, () -> 0.0).delayAfter(2);
        Duration high = new RetryPolicy(BASE, CAP, 0.2, () -> 0.999999).delayAfter(2);

        assertThat(low).isEqualTo(Duration.ofMillis(3200));   // 4s * 0.8
        assertThat(high).isBetween(Duration.ofMillis(4790), Duration.ofMillis(4800)); // ~4s * 1.2
    }

    @Test
    void zeroJitterIsDeterministic() {
        RetryPolicy policy = new RetryPolicy(BASE, CAP, 0, () -> 0.123);

        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void jitterNeverPushesDelayPastCap() {
        RetryPolicy policy = new RetryPolicy(BASE, CAP, 0.2, () -> 0.999999);

        assertThat(policy.delayAfter(20)).isEqualTo(CAP);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> noJitter().delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, CAP, 0.2, () -> 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(BASE, Duration.ofSeconds(1), 0.2, () -> 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(BASE, CAP, 1.5, () -> 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
