package dev.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobqueue.support.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestContainersConfig.class)
class ApplicationStartupIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    HealthEndpoint health;

    @Test
    void connectsToRealPostgres() {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void connectsToRealRedis() {
        assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualToIgnoringCase("PONG");
    }

    @Test
    void healthIsUp() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
}
