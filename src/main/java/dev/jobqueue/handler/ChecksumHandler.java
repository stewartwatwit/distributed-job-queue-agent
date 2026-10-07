package dev.jobqueue.handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Computes the SHA-256 of {@code {"text": "..."}} and returns it as lower-case hex. */
@Component
public class ChecksumHandler implements JobHandler {

    private final JsonMapper mapper;

    public ChecksumHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "CHECKSUM";
    }

    @Override
    public void validate(JsonNode payload) {
        JsonNode text = payload.get("text");
        if (text == null || !text.isString()) {
            throw new InvalidJobPayloadException("CHECKSUM requires a string field 'text'");
        }
    }

    @Override
    public JsonNode handle(JsonNode payload) throws NoSuchAlgorithmException {
        validate(payload);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(payload.get("text").asString().getBytes(StandardCharsets.UTF_8));
        ObjectNode result = mapper.createObjectNode();
        result.put("sha256", HexFormat.of().formatHex(digest));
        return result;
    }
}
