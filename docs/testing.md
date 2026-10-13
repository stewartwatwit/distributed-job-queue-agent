# Testing

| Suffix / location | Kind | Needs Docker | Run by |
|---|---|---|---|
| `*Test` | unit tests | no | `mvn test` (surefire) |
| `*IT` | integration tests against real PostgreSQL and Redis (Testcontainers) | yes | `mvn verify` (failsafe) |
| `*IT` tagged `@Tag("concurrency")`, in `dev.jobqueue.concurrency` | adversarial concurrency suite | yes | `mvn verify` |

Run only the concurrency suite: `mvn -B verify -Dtest='NoSuch*' -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='dev.jobqueue.concurrency.*IT'`.

## Concurrency suite

The suite tries to break the system with real threads against real infrastructure. It asserts outcomes (database rows and a handler-side execution counter), never timing. Waits use Awaitility and return as soon as the condition holds; threads are lined up with `CyclicBarrier`/`CountDownLatch`.

| Class | Provokes | Proves |
|---|---|---|
| `ClaimFencingIT` | 24 threads claim one QUEUED job; a zombie worker reports after its lease was reclaimed; a late `complete` races the sweeper's lease reclaim | exactly one claim wins (attempts == 1); losers and zombies are fenced (`complete`/`fail`/`scheduleRetry` return false); reclaim vs complete has exactly one winner, never a mixed state |
| `ExactlyOnceIT` | 200 jobs through one pool of 8 and through two independent pools of 4; 8 concurrent submitters; one id pushed 200 times from 8 threads while it runs | every job COMPLETED once, attempts == 1, distinct results, one handler execution per job |
| `CrashRecoveryIT` | workers claim and die; repeated deaths | dead attempts are retried and complete; attempts are never exceeded (no attempt max+1); final-attempt crash ends FAILED with a lease-expired error |
| `RedisDataLossIT` | `FLUSHALL` before workers run, mid-drain, and while retries wait in the delayed set | the sweeper's stale pass recovers every job, still exactly once |
| `RetryUnderLoadIT` | 96 flaky/failing/fatal jobs, two pools, delayed mover active | attempts <= max_attempts, expected terminal state, handler executions == attempts |
| `GracefulShutdownUnderLoadIT` | `stop()` mid-drain; `stop()` with a timeout shorter than the running jobs | no job left PROCESSING, none lost, in-flight work finishes (or is requeued on interrupt), a fresh pool finishes the rest with no re-execution |
| `WorkersAndSweepersTogetherIT` | two pools, the scheduled sweeper and two extra sweeper threads with a 200ms stale threshold | the resulting flood of duplicate messages never causes a second execution |

### Invariant checker

`JobInvariants` is used by every scenario. `assertRowInvariants` holds at any instant: PROCESSING rows have an owner and a lease, other rows have none, `attempts <= max_attempts`, terminal rows have `finished_at`, COMPLETED rows have a result, FAILED rows have `last_error`. `assertQuiescent` adds: every job terminal and Redis ready and delayed both empty.

### Conventions

- Workers are started by the tests (`ConcurrencyTestBase.startPool`) from the context's beans, so one test can run several pools with distinct instance ids; the context's own pool stays disabled.
- Every context that runs a sweeper or workers is `@DirtiesContext(AFTER_CLASS)`, and `jobs` plus the Redis keys are cleaned before each test, so a lingering context cannot steal another class's messages.
- `ConcurrencyHandlers` (`CONC_WORK`) counts handler starts and normal finishes per key; this is the independent witness for "executed exactly once".
