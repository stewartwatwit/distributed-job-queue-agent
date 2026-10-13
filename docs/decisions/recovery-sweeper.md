# Recovery sweeper

## Problem

PostgreSQL is the source of truth; Redis is a disposable hint queue with at-least-once delivery. Jobs can be stranded when the two disagree:

| Stranded state | Cause |
|---|---|
| `PENDING` | crash between the insert and `markQueued` |
| `QUEUED` with no Redis message | Redis down or flushed, worker died between pop and claim |
| `PROCESSING` with an expired lease | the worker died mid-job |
| id sitting in the delayed set | nobody moved it to the ready queue |

## Design

`recovery.Sweeper` (enabled by `jobqueue.sweeper.enabled`, default true) runs three independent scheduled tasks. Each body is a public method, so tests call it directly. Each swallows and logs exceptions, so a Redis outage never kills the scheduler.

1. **Delayed mover** (1s): `JobQueue.moveDueJobs(now, 100)` repeated until it returns 0, capped at 10 batches per run.
2. **Stale re-enqueue** (30s, threshold 5m): one statement, `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n) RETURNING id`, sets `QUEUED`, `queued_at` and `updated_at` to now. Then `queue.enqueue(id)` per returned id.
3. **Expired leases** (10s): same statement shape for `PROCESSING AND lease_expires_at < now()`. Attempts left means `QUEUED` and `queue.enqueueDelayed(id, now + RetryPolicy.delayAfter(attempts))`; otherwise `FAILED` with `last_error`. A crash consumes an attempt because attempts are counted at claim time.

Properties: `jobqueue.sweeper.{enabled, delayed-interval, stale-interval, lease-interval, stale-after, batch-size}`. The scheduler pool is 3 threads so a slow pass does not delay the others.

## Why this is safe

- **No double handling between sweepers.** Selection and update are one statement; `SKIP LOCKED` makes concurrent sweepers take disjoint rows (covered by the concurrency test).
- **Re-enqueue at most once per window.** The update bumps `updated_at`, so a row is not selected again until it is stale again.
- **Duplicate Redis messages are harmless.** The claim (`WHERE status = 'QUEUED'`) is the idempotency gate.
- **No lost update against a live worker.** The lease sweep matches only `PROCESSING` rows past their lease and clears `locked_by`. The original worker's later `complete`/`fail` is fenced by `locked_by` and affects 0 rows. A job completed before expiry is no longer `PROCESSING` and is never touched.
- **Time comes from the database** (`now()`), so sweepers and workers cannot disagree through clock skew.
- **DB first, Redis second.** If Redis fails after the DB update the row is already stamped; the next stale pass recovers it after the threshold.

## Tradeoffs

- **A deep backlog looks stale.** A job waiting longer than the threshold behind a long queue is re-enqueued and gets a second Redis message. The duplicate costs one no-op claim, never a double execution, but it inflates the queue. Mitigate with a threshold well above expected queue wait.
- **Recovery latency.** A lost message is repaired within threshold + sweep interval (about 5.5 minutes by default). Lowering the threshold trades latency for more duplicates.
- **Lease-expiry retry goes via the delayed set.** If that Redis write fails, the job waits for the stale pass (threshold + interval) instead of the backoff delay.
- **Every instance runs the sweeper.** That is deliberate: `SKIP LOCKED` and the atomic Redis move make it safe, so there is no leader election. The cost is some redundant polling.
- **Index.** `idx_jobs_status_queued_at` does not match the `updated_at` predicate. At this scale the scan is fine; an index on `(status, updated_at)` is the follow-up if the table grows.
