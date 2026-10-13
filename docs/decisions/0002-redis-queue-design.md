# ADR 0002: Redis queue = ready list + delayed sorted set, ids only

## Context

The spec requires Redis to take a meaningful part in processing: FIFO order, concurrent consumers,
failed jobs, duplicate handling and queue/database consistency.

## Decision

| Key | Type | Operations |
|---|---|---|
| `jobqueue:ready` | LIST | producers `LPUSH`; workers blocking `BRPOP` (oldest first, each id to exactly one consumer) |
| `jobqueue:delayed` | ZSET, score = due time in epoch ms | retries `ZADD`; `move-due.lua` moves due ids to `ready` |

- **Only the job id is stored.** The payload and state live in PostgreSQL, so there is nothing to
  serialize, version or let drift.
- **Delivery is at-least-once and Redis is treated as a hint.** A popped id only tells a worker
  where to look; PostgreSQL's claim decides whether the job runs.
- **Delayed move is one Lua script** (`ZRANGEBYSCORE … LIMIT` + `ZREM` + `LPUSH`). Redis runs a
  script atomically, so two nodes moving at once can never move an id twice.
- **Recovery has a single mechanism**: if a message is lost (Redis restart, crash between pop and
  claim), the job is still `QUEUED` in PostgreSQL and the sweeper re-enqueues it
  ([sweeper design](0005-recovery-sweeper.md)).
- **No Redis dead-letter list.** Permanently failed jobs are `FAILED` rows, queryable through the API.

## Alternatives considered

| Option | Trade-off |
|---|---|
| Reliable-queue pattern (`BLMOVE` into a per-worker processing list) | Survives pop-then-crash without sweeper help, but adds a second recovery mechanism (per-worker lists to reclaim) next to the PostgreSQL lease, and the two can disagree |
| Redis Streams + consumer groups (`XREADGROUP`, `XACK`, `XAUTOCLAIM`) | The most idiomatic Redis primitive, with built-in pending tracking. More API surface and a pending-entry list to keep consistent with PostgreSQL; more machinery than this problem needs |
| Delay via PostgreSQL only (`next_run_at` polled by the sweeper) | One fewer Redis structure, but Redis would then not take part in retry scheduling and PostgreSQL would be polled |
| Store the full job in Redis | Duplicates state, invites inconsistency, loses the single source of truth |

## Consequences

- Losing all of Redis delays but never loses jobs.
- A deep backlog can make waiting jobs look stale and receive duplicate messages (harmless, see ADR 0001).
- `BRPOP` with Spring Data Redis/Lettuce uses a dedicated connection per call; at the default pop
  timeout that is only a few connections every couple of seconds. A connection pool is an
  optimisation if worker counts grow.
