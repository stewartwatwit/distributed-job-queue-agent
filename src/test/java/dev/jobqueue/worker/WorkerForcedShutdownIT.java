package dev.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobqueue.job.Job;
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

/**
 * A job that outlasts the shutdown timeout is interrupted. The interruption is recorded like any
 * other failed attempt (the job goes back to QUEUED with its error) instead of being stranded
 * PROCESSING until the lease expires.
 */
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=1",
        "jobqueue.worker.pop-timeout=200ms",
        "jobqueue.worker.shutdown-timeout=1s"
})
@Import(TestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerForcedShutdownIT {

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
    void jobInterruptedByShutdownTimeoutIsRecordedAsAFailedAttempt() {
        UUID id = jobs.submit("SLEEP", "{\"millis\":30000}", 3).getId();
        jobs.markQueued(id);
        queue.enqueue(id);
        await().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(id).getStatus() == JobStatus.PROCESSING);

        pool.stop();

        Job job = jobs.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("InterruptedException");
        assertThat(job.getLockedBy()).isNull();
        assertThat(queue.delayedSize()).isEqualTo(1);
    }
}
