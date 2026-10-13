# ADR 0004: Retry and failure handling

## Decision

| Question | Answer |
|---|---|
| Are all failures retryable? | Yes, except `NonRetryableJobException` and its subclasses (unknown job type, invalid payload) |
| Maximum attempts | `max_attempts` per job: client may set 1–10, default 3. Never unlimited |
| How are attempts tracked? | `attempts` is incremented by the claim, i.e. when an attempt *starts* |
| Backoff | `min(cap, base · 2^(n-1) · (1 ± jitter))`; defaults base 2s, cap 60s, jitter 0.2. Pure class `RetryPolicy` with an injectable random source |
| What happens on a retry? | row → `QUEUED`, `last_error` saved, id → Redis delayed set with the backoff delay |
| After the final failure | row → `FAILED`, `last_error` and `finished_at` kept, never retried automatically |
| What if a worker dies mid-job? | the lease expires; the sweeper treats it like a failed attempt (retry with backoff, or `FAILED` if none are left) |

## Reasoning

- **Counting at claim time** means a job that crashes its worker still burns an attempt, so a
  "poison" job cannot loop forever through lease expiry.
- **Whether a job may be retried is decided by callers** (`JobRunner`, `Sweeper`); `RetryPolicy`
  only computes the delay, which keeps it trivially testable.
- **Every handler `Throwable` is recorded**, including `InterruptedException` (shutdown timeout) and
  `StackOverflowError`. Only fatal JVM errors (`OutOfMemoryError`, …) propagate, because the process
  is no longer trustworthy. Found in concurrency review: swallowing interrupts used to leave the
  thread's interrupt flag set and spin the worker loop, and not recording `Error`s stranded jobs
  until their lease expired.
- Failures never take a worker down: unexpected errors in the poll loop are logged, the worker backs
  off for a second and continues (typical cause: Redis or PostgreSQL briefly unavailable).

## Known limitations

- The delay before a retry exists only in the Redis delayed set. If Redis is lost, retries resume after
  the sweeper's stale threshold instead of the original backoff, and a duplicate message for a
  `QUEUED` job could start a retry early. A `next_run_at` column checked by the claim would close
  this; it was left out to keep the model small.
- Lease expiry is the only timeout. There is no heartbeat, so choose `jobqueue.job.lease` longer than
  the slowest job (a job that outlives its lease may run twice; fenced writes keep state correct).
