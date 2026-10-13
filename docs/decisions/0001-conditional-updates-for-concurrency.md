# ADR 0001: Conditional UPDATEs are the concurrency gate

## Context

Several workers (in one or many processes) pop job ids concurrently, ids can be duplicated, and a
worker can stall or die and later wake up. We need exactly one worker to run a job at a time and
stale workers must not corrupt newer state.

## Decision

Every job state change is a single SQL statement whose `WHERE` clause names the only state it may
start from; the affected row count says whether the caller won.

- **Claim:** `UPDATE jobs SET status='PROCESSING', attempts=attempts+1, locked_by=?, lease_expires_at=now()+lease … WHERE id=? AND status='QUEUED' AND attempts < max_attempts`. One row → this worker owns the job. Zero → duplicate or stale message; drop it.
- **Finish (complete / retry / fail):** `… WHERE id=? AND status='PROCESSING' AND locked_by=?`. A worker that lost its lease affects zero rows and its outcome is discarded.
- **Entities are only used to insert and read.** There is no read-modify-write on `Job`.
- The lease deadline uses the database clock (`now()`), so clock skew between nodes cannot matter.
- The handler runs with **no transaction open**; each state change is its own short transaction.

## Alternatives considered

| Option | Why not |
|---|---|
| JPA `@Version` optimistic locking | Gives the same guarantee but needs catch-and-retry of `OptimisticLockException` around every transition; the conditional UPDATE is less code and easier to reason about |
| `SELECT … FOR UPDATE SKIP LOCKED` as the queue | Would make Redis redundant, and the project requires Redis to take part in dispatch. (`SKIP LOCKED` is used, but only by the sweeper to keep concurrent sweepers on disjoint rows) |
| Distributed lock in Redis (`SET NX`) | A second source of truth that can disagree with PostgreSQL; fencing would still be needed |
| Pessimistic row lock held while the job runs | Pins a connection for the whole job; contradicts "no transaction during execution" |

## Consequences

- Correctness does not depend on Redis delivering each id exactly once; duplicates are harmless.
- Attempts are counted at claim time, so a crashed attempt still consumes one and retries are bounded.
- `claim` also refuses jobs with no attempts left, as defence against stray messages.
- A slow worker that finishes after losing its lease wastes its work (result discarded) but cannot
  damage state. Handlers must therefore be idempotent (at-least-once execution).
