package dev.jobqueue.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link JobQueue}.
 *
 * <ul>
 *   <li>{@code <prefix>:ready} - LIST. LPUSH to enqueue, BRPOP to consume, so the oldest id comes
 *       out first and BRPOP gives each id to exactly one blocked consumer.</li>
 *   <li>{@code <prefix>:delayed} - ZSET scored by due time (epoch millis), used for retry backoff.
 *       A Lua script moves due ids to the ready list atomically.</li>
 * </ul>
 * Only job ids are stored; the payload stays in PostgreSQL, which is the source of truth.
 */
@Component
public class RedisJobQueue implements JobQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisJobQueue.class);

    private final StringRedisTemplate redis;
    private final String readyKey;
    private final String delayedKey;
    private final DefaultRedisScript<Long> moveDueScript;

    public RedisJobQueue(StringRedisTemplate redis,
                         @Value("${jobqueue.queue.key-prefix:jobqueue}") String keyPrefix) {
        this.redis = redis;
        this.readyKey = keyPrefix + ":ready";
        this.delayedKey = keyPrefix + ":delayed";
        this.moveDueScript = new DefaultRedisScript<>();
        this.moveDueScript.setLocation(new ClassPathResource("redis/move-due.lua"));
        this.moveDueScript.setResultType(Long.class);
    }

    @Override
    public void enqueue(UUID jobId) {
        redis.opsForList().leftPush(readyKey, jobId.toString());
    }

    @Override
    public void enqueueDelayed(UUID jobId, Instant dueAt) {
        redis.opsForZSet().add(delayedKey, jobId.toString(), dueAt.toEpochMilli());
    }

    @Override
    public Optional<UUID> pop(Duration timeout) {
        String value = redis.opsForList().rightPop(readyKey, timeout);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            log.warn("Discarding malformed job id '{}' from {}", value, readyKey);
            return Optional.empty();
        }
    }

    @Override
    public int moveDueJobs(Instant now, int limit) {
        Long moved = redis.execute(moveDueScript, List.of(delayedKey, readyKey),
                Long.toString(now.toEpochMilli()), Integer.toString(limit));
        return moved == null ? 0 : moved.intValue();
    }

    @Override
    public long readySize() {
        Long size = redis.opsForList().size(readyKey);
        return size == null ? 0 : size;
    }

    @Override
    public long delayedSize() {
        Long size = redis.opsForZSet().zCard(delayedKey);
        return size == null ? 0 : size;
    }
}
