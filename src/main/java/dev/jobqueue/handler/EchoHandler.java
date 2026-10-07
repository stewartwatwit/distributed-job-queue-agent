package dev.jobqueue.handler;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Returns the payload unchanged, wrapped as {@code {"echo": payload}}. Useful for smoke tests. */
@Component
public class EchoHandler implements JobHandler {

    private final JsonMapper mapper;

    public EchoHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "ECHO";
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        ObjectNode result = mapper.createObjectNode();
        result.set("echo", payload);
        return result;
    }
}
