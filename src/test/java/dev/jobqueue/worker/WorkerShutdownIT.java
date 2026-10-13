package dev.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.queue.JobQueue;
import dev.jobqueue.support.TestContainersConfig;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=2",
        "jobqueue.worker.pop-timeout=300ms",
        "jobqueue.worker.shutdown-timeout=10s"
})
@Import(TestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerShutdownIT {

    @Autowired
    WorkerPool pool;

    @Autowired
    JobService jobs;

    @Autowired
    JobQueue queue;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
        redis.delete(List.of("jobqueue:ready", "jobqueue:delayed"));
    }

    @Test
    void stopLetsInFlightJobFinishAndThenStopsConsuming() {
        UUID running = jobs.submit("SLEEP", "{\"millis\":1500}", null).getId();
        jobs.markQueued(running);
        queue.enqueue(running);
        await().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(running).getStatus() == JobStatus.PROCESSING);

        pool.stop();

        assertThat(pool.isRunning()).isFalse();
        assertThat(jobs.get(running).getStatus()).isEqualTo(JobStatus.COMPLETED);

        UUID afterStop = jobs.submit("ECHO", "{}", null).getId();
        jobs.markQueued(afterStop);
        queue.enqueue(afterStop);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> jobs.get(afterStop).getStatus() == JobStatus.QUEUED);
        assertThat(queue.readySize()).isEqualTo(1);
    }
}
