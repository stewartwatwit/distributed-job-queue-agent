package dev.jobqueue.handler;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Sleeps for {@code {"millis": n}} (0..60000). Simulates slow work so concurrency is observable. */
@Component
public class SleepHandler implements JobHandler {

    static final long MAX_MILLIS = 60_000;

    private final JsonMapper mapper;

    public SleepHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "SLEEP";
    }

    @Override
    public void validate(JsonNode payload) {
        JsonNode millis = payload.get("millis");
        if (millis == null || !millis.canConvertToLong() || millis.asLong() < 0 || millis.asLong() > MAX_MILLIS) {
            throw new InvalidJobPayloadException("SLEEP requires 'millis' between 0 and " + MAX_MILLIS);
        }
    }

    @Override
    public JsonNode handle(JsonNode payload) throws InterruptedException {
        validate(payload);
        long millis = payload.get("millis").asLong();
        Thread.sleep(millis);
        ObjectNode result = mapper.createObjectNode();
        result.put("sleptMillis", millis);
        return result;
    }
}
