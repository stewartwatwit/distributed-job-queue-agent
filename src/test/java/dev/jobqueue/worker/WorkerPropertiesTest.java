package dev.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerPropertiesTest {

    @Test
    void acceptsSaneValues() {
        assertThatCode(() -> new WorkerProperties(4, Duration.ofSeconds(2), Duration.ofSeconds(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNoWorkers() {
        assertThatThrownBy(() -> new WorkerProperties(0, Duration.ofSeconds(2), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concurrency");
    }

    @Test
    void rejectsZeroPopTimeoutWhichWouldBlockForever() {
        assertThatThrownBy(() -> new WorkerProperties(4, Duration.ZERO, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pop-timeout");
    }

    @Test
    void rejectsPopTimeoutNotShorterThanShutdownTimeout() {
        assertThatThrownBy(() -> new WorkerProperties(4, Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shorter");
    }
}
