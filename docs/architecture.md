# Architecture

A Spring Boot application that accepts jobs over REST, stores them in PostgreSQL, dispatches them
through Redis, and runs them on a pool of worker threads. One codebase and one image: the same jar
can run as an API node, a worker node, or both, and any number of nodes can share the same
PostgreSQL and Redis.

**Guiding principle: PostgreSQL is the source of truth; Redis is a fast, disposable dispatch
channel.** Anything lost from Redis can be rebuilt from PostgreSQL.

```
Client ──HTTP──> JobController ──> JobSubmissionService ──1. insert PENDING, 2. mark QUEUED──> PostgreSQL
                                                       └──3. push id──┐
                                                                      v
                                   Redis  ready LIST  <──mover── delayed ZSET (retry backoff)
                                      │ BRPOP
                                      v
              WorkerPool (N threads) ──> JobRunner ──claim (conditional UPDATE)──> PostgreSQL
                                           │ run the JobHandler for the job's type (no transaction held)
                                           └──complete / retry / fail (fenced UPDATE)──> PostgreSQL

        Sweeper (scheduled, every node): re-enqueue stranded jobs, expire dead leases, move due retries
```

## Packages

| Package | Responsibility |
|---|---|
| `job` | `Job` entity, `JobStatus` lifecycle, `JobRepository` (conditional UPDATEs), `JobService` (short transactions), `JobSubmissionService` (validate → persist → enqueue) |
| `api` | `JobController`, request/response records, `ApiExceptionHandler` (RFC 9457 `ProblemDetail`) |
| `queue` | `JobQueue` interface and `RedisJobQueue` (ready list, delayed set, Lua mover) |
| `handler` | `JobHandler` SPI, `JobHandlerRegistry`, `ECHO` / `SLEEP` / `CHECKSUM` handlers, non-retryable exceptions |
| `worker` | `WorkerPool` (`SmartLifecycle`), `JobRunner`, `RetryPolicy` |
| `recovery` | `Sweeper` |
| `config` | clock, retry policy bean, scheduling |

Adding a job type is one new `@Component` implementing `JobHandler`; nothing else changes.

## Job lifecycle

```
PENDING ──markQueued──> QUEUED ──claim──> PROCESSING ──ok──────────────────────> COMPLETED
                           ^                  │
                           └─ retryable error ┤  (attempts left; id goes to the delayed set with backoff)
                                              └─ final / non-retryable error ───> FAILED
PROCESSING ──lease expired (sweeper)──> QUEUED (attempts left) or FAILED
```

`COMPLETED` and `FAILED` are terminal. `PENDING` is the short window between the insert and the
moment the job is released to workers. `FAILED` rows are the dead-letter store: they keep
`last_error` and are listed with `GET /api/v1/jobs?status=FAILED`.

## Data model (`jobs`)

Schema is owned by Flyway (`V1__create_jobs.sql`); Hibernate only validates it.

| Column(s) | Purpose |
|---|---|
| `id` (UUID), `type`, `payload` (jsonb) | identity, handler key, input |
| `status` (CHECK constrained) | lifecycle state |
| `attempts`, `max_attempts` | attempts are counted when a worker claims the job |
| `last_error`, `result` (jsonb) | failure detail (truncated to 2000 chars), handler output |
| `locked_by`, `lease_expires_at` | which worker holds the job and until when |
| `created_at`, `updated_at`, `queued_at`, `started_at`, `finished_at` | timestamps |

Indexes: `(status, queued_at)`, partial `(lease_expires_at) WHERE status = 'PROCESSING'`,
`(created_at DESC)`.

## Redis structures

| Key | Type | Use |
|---|---|---|
| `jobqueue:ready` | LIST | `LPUSH` to enqueue, `BRPOP` to consume: FIFO, one consumer per id |
| `jobqueue:delayed` | ZSET (score = due epoch ms) | retry backoff; `move-due.lua` atomically moves due ids to `ready` |

Only job ids are stored; payloads never leave PostgreSQL. See
[ADR 0002](decisions/0002-redis-queue-design.md).

## How concurrency is handled

| Hazard | Mechanism |
|---|---|
| Two workers run one job | the claim, `UPDATE … WHERE status = 'QUEUED' AND attempts < max_attempts`, succeeds for exactly one caller |
| Duplicate Redis messages | same claim; the loser drops the message |
| A slow or dead worker overwrites newer state | outcomes are fenced: `WHERE status = 'PROCESSING' AND locked_by = <worker>` |
| Two sweepers or movers act at once | row selection uses `FOR UPDATE SKIP LOCKED` inside one statement; the Lua move is atomic |
| PostgreSQL and Redis disagree | commit first, then push; the sweeper repairs gaps from PostgreSQL |
| Worker crash | lease expiry → sweeper retries or fails the job; the lost attempt still counts |
| Shared in-memory state | none beyond one `volatile` run flag in `WorkerPool`; workers are otherwise stateless |
| Shutdown | stop flag + bounded wait for in-flight jobs; leftovers are recovered by lease expiry |

No transaction is ever open while a handler runs, and lease times use the database clock. Details:
[ADR 0001](decisions/0001-conditional-updates-for-concurrency.md),
[sweeper design](decisions/recovery-sweeper.md).

## Failure handling and retries

- Any exception is retryable except `NonRetryableJobException` (including unknown type and invalid
  payload). A permanent failure goes straight to `FAILED`.
- `max_attempts` defaults to 3 (client may set 1–10). Backoff is exponential with jitter, capped at 60s.
- A bad job can never kill a worker: `JobRunner` records every handler `Throwable` (only fatal JVM
  errors such as `OutOfMemoryError` propagate).

See [ADR 0004](decisions/0004-retry-and-failure-handling.md).

## Configuration

All settings can be overridden with environment variables (Spring relaxed binding, e.g.
`JOBQUEUE_WORKER_CONCURRENCY`).

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.url/username/password` | local dev values | PostgreSQL (`SPRING_DATASOURCE_*`) |
| `spring.data.redis.host/port` | `localhost` / `6379` | Redis (`SPRING_DATA_REDIS_*`) |
| `jobqueue.worker.enabled` | `true` | run a worker pool in this process |
| `jobqueue.worker.concurrency` | `4` | worker threads |
| `jobqueue.worker.pop-timeout` | `2s` | how long a worker blocks on the queue |
| `jobqueue.worker.shutdown-timeout` | `30s` | wait for in-flight jobs on stop |
| `jobqueue.job.default-max-attempts` | `3` | attempts when the client does not say |
| `jobqueue.job.lease` | `5m` | how long a claimed job may run before it is considered abandoned |
| `jobqueue.retry.base-delay` / `max-delay` / `jitter` | `2s` / `60s` / `0.2` | backoff |
| `jobqueue.queue.key-prefix` | `jobqueue` | Redis key prefix |
| `jobqueue.sweeper.enabled` | `true` | run the sweeper in this process |
| `jobqueue.sweeper.delayed-interval` / `stale-interval` / `lease-interval` | `1s` / `30s` / `10s` | sweeper task cadence |
| `jobqueue.sweeper.stale-after` | `5m` | age at which a PENDING/QUEUED job is considered stranded; keep well above `retry.max-delay` |
| `jobqueue.sweeper.batch-size` | `100` | rows per sweeper statement |

## Known limitations

- **At-least-once.** A job can run more than once (a worker outliving its lease, a crash after the
  handler finished but before the result was saved). Handlers should be idempotent. Fenced writes
  keep the stored state consistent regardless.
- **No lease heartbeat or per-job timeout.** `jobqueue.job.lease` must exceed the longest expected
  job.
- **Retry timing lives in Redis.** The row records that a job is `QUEUED`, not when its backoff
  ends. A rebuilt queue (after Redis loss) retries stranded jobs after the sweeper's stale
  threshold, not the original backoff.
- **No authentication, idempotency keys, or manual re-run endpoint** (out of scope for v1).
