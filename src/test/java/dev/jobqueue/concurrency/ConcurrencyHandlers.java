package dev.jobqueue.concurrency;

import dev.jobqueue.handler.JobHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler used by the concurrency suite. Payload: {@code {"key": "...", "millis": n,
 * "slowFirstOnly": bool}}. Every invocation is counted when it starts and again when it returns
 * normally, so tests can prove "executed exactly once" and "never abandoned half way". The result
 * echoes the key and which invocation produced it, so duplicated or mixed-up results are visible.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConcurrencyHandlers {

    public static final String TYPE = "CONC_WORK";

    /** Invocations that started, keyed by payload key. */
    public static final Map<String, AtomicInteger> STARTED = new ConcurrentHashMap<>();

    /** Invocations that returned normally (were not interrupted or failed), keyed by payload key. */
    public static final Map<String, AtomicInteger> FINISHED = new ConcurrentHashMap<>();

    public static void reset() {
        STARTED.clear();
        FINISHED.clear();
    }

    public static int started(String key) {
        AtomicInteger count = STARTED.get(key);
        return count == null ? 0 : count.get();
    }

    public static int finished(String key) {
        AtomicInteger count = FINISHED.get(key);
        return count == null ? 0 : count.get();
    }

    public static String payload(String key, long millis) {
        return "{\"key\":\"" + key + "\",\"millis\":" + millis + "}";
    }

    @Bean
    JobHandler concurrencyWorkHandler(JsonMapper mapper) {
        return new JobHandler() {
            @Override
            public String type() {
                return TYPE;
            }

            @Override
            public JsonNode handle(JsonNode payload) throws Exception {
                String key = payload.get("key").asString();
                int execution = STARTED.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                boolean slowFirstOnly = payload.has("slowFirstOnly") && payload.get("slowFirstOnly").asBoolean();
                long millis = payload.get("millis").asLong();
                if (millis > 0 && (!slowFirstOnly || execution == 1)) {
                    Thread.sleep(millis);
                }
                FINISHED.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                return mapper.createObjectNode().put("key", key).put("execution", execution);
            }
        };
    }
}
