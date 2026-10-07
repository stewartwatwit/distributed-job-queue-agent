package dev.jobqueue.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Drives the REST API end to end against real PostgreSQL and Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jobqueue.worker.enabled=false")
@AutoConfigureTestRestTemplate
@Import(TestContainersConfig.class)
class JobApiIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JobQueue queue;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
    }

    @Test
    void submittedJobIsStoredQueuedAndEnqueued() {
        ResponseEntity<JsonNode> created = rest.postForEntity("/api/v1/jobs",
                new SubmitJobRequest("ECHO", json("{\"hello\":\"world\"}"), 2), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        UUID id = UUID.fromString(body.get("id").asString());
        assertThat(created.getHeaders().getLocation()).hasPath("/api/v1/jobs/" + id);
        assertThat(body.get("status").asString()).isEqualTo("QUEUED");
        assertThat(body.get("maxAttempts").asInt()).isEqualTo(2);

        JsonNode fetched = rest.getForObject("/api/v1/jobs/{id}", JsonNode.class, id);
        assertThat(fetched.get("type").asString()).isEqualTo("ECHO");
        assertThat(fetched.get("payload").get("hello").asString()).isEqualTo("world");
        assertThat(fetched.get("status").asString()).isEqualTo("QUEUED");

        JsonNode status = rest.getForObject("/api/v1/jobs/{id}/status", JsonNode.class, id);
        assertThat(status.get("status").asString()).isEqualTo("QUEUED");
        assertThat(status.get("attempts").asInt()).isZero();

        assertThat(queue.readySize()).isEqualTo(1);
        assertThat(queue.pop(Duration.ofSeconds(1))).contains(id);
    }

    @Test
    void listFiltersByStatus() {
        rest.postForEntity("/api/v1/jobs", new SubmitJobRequest("ECHO", json("{}"), null), JsonNode.class);

        JsonNode queued = rest.getForObject("/api/v1/jobs?status=QUEUED", JsonNode.class);
        JsonNode failed = rest.getForObject("/api/v1/jobs?status=FAILED", JsonNode.class);

        assertThat(queued.get("totalElements").asInt()).isEqualTo(1);
        assertThat(failed.get("totalElements").asInt()).isZero();
    }

    @Test
    void unknownTypeIsRejectedAndNothingIsStored() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/jobs",
                new SubmitJobRequest("NOPE", json("{}"), null), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
        assertThat(queue.readySize()).isZero();
    }

    @Test
    void invalidPayloadIsRejected() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/jobs",
                new SubmitJobRequest("SLEEP", json("{\"millis\":-1}"), null), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
    }

    @Test
    void unknownJobIs404() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/jobs/{id}", JsonNode.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openApiDocumentIsServed() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("paths").has("/api/v1/jobs")).isTrue();
    }

    private static JsonNode json(String text) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(text);
    }
}
