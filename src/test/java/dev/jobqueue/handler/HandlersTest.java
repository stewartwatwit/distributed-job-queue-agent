package dev.jobqueue.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HandlersTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    @Test
    void echoWrapsPayload() {
        JsonNode result = new EchoHandler(mapper).handle(json("{\"a\":1}"));

        assertThat(result).isEqualTo(json("{\"echo\":{\"a\":1}}"));
    }

    @Test
    void checksumComputesSha256() throws Exception {
        JsonNode result = new ChecksumHandler(mapper).handle(json("{\"text\":\"abc\"}"));

        assertThat(result.get("sha256").asString())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void checksumRejectsMissingOrNonStringText() {
        ChecksumHandler handler = new ChecksumHandler(mapper);

        assertThatThrownBy(() -> handler.validate(json("{}"))).isInstanceOf(InvalidJobPayloadException.class);
        assertThatThrownBy(() -> handler.validate(json("{\"text\":5}"))).isInstanceOf(InvalidJobPayloadException.class);
    }

    @Test
    void sleepSleepsAndReports() throws Exception {
        long start = System.nanoTime();

        JsonNode result = new SleepHandler(mapper).handle(json("{\"millis\":50}"));

        assertThat(result.get("sleptMillis").asLong()).isEqualTo(50);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(45);
    }

    @Test
    void sleepRejectsOutOfRangeDurations() {
        SleepHandler handler = new SleepHandler(mapper);

        assertThatThrownBy(() -> handler.validate(json("{}"))).isInstanceOf(InvalidJobPayloadException.class);
        assertThatThrownBy(() -> handler.validate(json("{\"millis\":-1}"))).isInstanceOf(InvalidJobPayloadException.class);
        assertThatThrownBy(() -> handler.validate(json("{\"millis\":60001}"))).isInstanceOf(InvalidJobPayloadException.class);
        assertThatThrownBy(() -> handler.validate(json("{\"millis\":\"x\"}"))).isInstanceOf(InvalidJobPayloadException.class);
    }

    @Test
    void invalidPayloadAndUnknownTypeAreNonRetryable() {
        assertThat(new InvalidJobPayloadException("x")).isInstanceOf(NonRetryableJobException.class);
        assertThat(new UnknownJobTypeException("X")).isInstanceOf(NonRetryableJobException.class);
    }

    @Test
    void registryLooksUpHandlersByType() {
        EchoHandler echo = new EchoHandler(mapper);
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(echo, new SleepHandler(mapper)));

        assertThat(registry.supports("ECHO")).isTrue();
        assertThat(registry.supports("NOPE")).isFalse();
        assertThat(registry.get("ECHO")).isSameAs(echo);
        assertThat(registry.types()).containsExactly("ECHO", "SLEEP");
        assertThatThrownBy(() -> registry.get("NOPE")).isInstanceOf(UnknownJobTypeException.class);
    }

    @Test
    void registryRejectsDuplicateTypes() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(new EchoHandler(mapper), new EchoHandler(mapper))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ECHO");
    }
}
