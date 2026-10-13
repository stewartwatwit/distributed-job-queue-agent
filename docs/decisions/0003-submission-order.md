# ADR 0003: Submission order: insert, mark QUEUED, then push to Redis

## Context

A submission touches two systems that cannot share a transaction. The early design pushed the id
to Redis and then flipped the row from `PENDING` to `QUEUED`.

## Problem with that order

A fast worker can pop the id before the row becomes `QUEUED`. The claim only accepts `QUEUED` rows,
so the claim fails and the message is silently dropped; the job then waits for the sweeper.

## Decision

`JobSubmissionService.submit` does, in this order and deliberately **not** in one transaction:

1. validate type and payload (nothing is stored for a rejected request);
2. insert the row as `PENDING` (committed);
3. `markQueued` (`PENDING → QUEUED`, committed);
4. push the id to Redis.

If step 4 fails the job is already durably `QUEUED`; the API still returns `201` and the sweeper
re-enqueues it once it is stale. A crash between steps 2 and 3 leaves a `PENDING` row, which the
sweeper also re-enqueues.

`PENDING` therefore means "stored but not yet released to workers", not "not yet in Redis".

## Alternatives considered

| Option | Why not |
|---|---|
| Push first, then mark `QUEUED` | The race above |
| Push inside the DB transaction | The id can be visible before the commit; a rollback leaves an orphan id |
| Transactional outbox + relay | Correct and standard, but a second table and a relay process for a gap the sweeper already closes |
| Return `503` when Redis is down | The job is already safely stored; failing the client would force needless retries and duplicates |

## Consequences

- Worst case after a Redis outage or crash is latency (up to the sweeper's stale threshold), never loss.
- The response to a submit may show `QUEUED` for a job whose id has not reached Redis yet.
