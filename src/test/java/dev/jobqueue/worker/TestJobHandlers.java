package dev.jobqueue.worker;

import dev.jobqueue.handler.JobHandler;
import dev.jobqueue.handler.NonRetryableJobException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic handlers used only by tests; they record how often each job was executed. */
@TestConfiguration(proxyBeanMethods = false)
public class TestJobHandlers {

    /** Invocation count keyed by the payload's "key" field. */
    public static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

    static int record(JsonNode payload) {
        String key = payload.get("key").asString();
        return EXECUTIONS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    public static int executions(String key) {
        AtomicInteger count = EXECUTIONS.get(key);
        return count == null ? 0 : count.get();
    }

    /** Succeeds and counts executions. */
    @Bean
    JobHandler countingHandler(JsonMapper mapper) {
        return handler("TEST_COUNT", payload -> {
            record(payload);
            return mapper.createObjectNode().put("done", true);
        });
    }

    /** Fails on its first {@code failFirst} invocations for a key, then succeeds. */
    @Bean
    JobHandler flakyHandler(JsonMapper mapper) {
        return handler("TEST_FLAKY", payload -> {
            int n = record(payload);
            if (n <= payload.get("failFirst").asInt()) {
                throw new IllegalStateException("transient failure #" + n);
            }
            return mapper.createObjectNode().put("succeededOnInvocation", n);
        });
    }

    /** Always throws a retryable exception. */
    @Bean
    JobHandler alwaysFailHandler() {
        return handler("TEST_FAIL", payload -> {
            record(payload);
            throw new IllegalStateException("always fails");
        });
    }

    /** Always throws a non-retryable exception. */
    @Bean
    JobHandler fatalHandler() {
        return handler("TEST_FATAL", payload -> {
            record(payload);
            throw new NonRetryableJobException("cannot ever work");
        });
    }

    interface Body {
        JsonNode run(JsonNode payload) throws Exception;
    }

    private static JobHandler handler(String type, Body body) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public JsonNode handle(JsonNode payload) throws Exception {
                return body.run(payload);
            }
        };
    }
}
