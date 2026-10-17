# Running with Docker

## Infrastructure only (day-to-day development)

```bash
docker compose up -d      # postgres:5432 + redis:6379 only
mvn spring-boot:run      # or run from the IDE; defaults match compose
```

## Full stack (api + workers in containers)

The application services are behind the opt-in `app` profile, so the plain command above is unchanged.

```bash
docker compose --profile app up -d --build --scale worker=3
curl http://localhost:8080/actuator/health
docker compose --profile app down        # add -v to also delete the database volume
```

| Service  | Role | Config |
|----------|------|--------|
| `api`    | REST API on `:8080`, runs the recovery sweeper and Flyway migrations | worker pool off, sweeper on |
| `worker` | Processes jobs, no published ports, scalable | worker pool on, sweeper off |

Both use the same image (`jobqueue-app:local`, built by `api`). Workers wait for `api` to be healthy so migrations
run once, before any worker starts. Total parallelism is `replicas x JOBQUEUE_WORKER_CONCURRENCY`.
Only one instance needs the sweeper, but it is safe to run more (see `decisions/recovery-sweeper.md`).

Scale at any time: `docker compose --profile app up -d --scale worker=5`.

## Smoke test

`scripts/smoke-test.sh` builds the image, starts the stack with 3 workers, submits 30 mixed jobs
(ECHO/CHECKSUM/SLEEP), waits for all to be `COMPLETED`, checks results (CHECKSUM is verified against `sha256sum`),
then runs `docker compose down -v`. On failure it prints `compose ps` and the last 60 log lines per service.
It uses its own compose project (`jobqueue-smoke`) and ports (18080/15432/16379), so it will not touch a running dev stack.
Takes about 2 minutes (mostly the first image build). CI runs it as the `docker-smoke` job.

## Configuration reference

Set in your shell or a local `.env` (copy `.env.example`; `.env` is gitignored). All have dev-only defaults.

| Variable | Default | Meaning |
|----------|---------|---------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `jobqueue` | Database name and credentials; used by postgres and passed to the apps as `SPRING_DATASOURCE_*` |
| `POSTGRES_PORT` | `5432` | Host port for postgres |
| `REDIS_PORT` | `6379` | Host port for redis |
| `API_PORT` | `8080` | Host port for the api |
| `JOBQUEUE_WORKER_CONCURRENCY` | `4` | Worker threads per `worker` container |

Set inside the app containers by `compose.yaml` (Spring relaxed binding maps them to properties):

| Variable | Value | Property |
|----------|-------|----------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/<db>` | `spring.datasource.url` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | from `POSTGRES_*` | `spring.datasource.*` |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | `redis` / `6379` | `spring.data.redis.*` |
| `JOBQUEUE_WORKER_ENABLED` | `false` api, `true` worker | `jobqueue.worker.enabled` |
| `JOBQUEUE_SWEEPER_ENABLED` | `true` api, `false` worker | `jobqueue.sweeper.enabled` |

Other `jobqueue.*` properties can be set the same way by adding e.g. `JOBQUEUE_SWEEPER_STALE_AFTER` to a service's `environment`.

## Image notes

Multi-stage build: Maven/Temurin 21 compiles (tests skipped; CI runs them), then a Temurin 21 JRE on Alpine runs the jar
as a non-root user with `-XX:MaxRAMPercentage=75`. The JVM is PID 1 (exec-form entrypoint), so `docker stop` triggers
Spring's graceful shutdown and workers drain in-flight jobs (30s; compose's default stop grace period is 10s, so raise
`stop_grace_period` if you run long jobs). The image healthcheck uses busybox `wget` against `/actuator/health`
(the Alpine JRE image has no `curl`).

## Resetting data

```bash
docker compose --profile app down -v     # removes the postgres volume; Redis is not persisted
```

## Troubleshooting

- **Port already in use**: set `POSTGRES_PORT`, `REDIS_PORT` or `API_PORT` in `.env`.
- **Workers not starting**: they wait for `api` to be healthy; check `docker compose --profile app logs api`.
- **Stale image after code changes**: add `--build`.
- **Changed `POSTGRES_PASSWORD` and auth fails**: the volume keeps the old password; `down -v` to reset.
- **Windows**: `.gitattributes` forces LF for `*.sh`; run the smoke test from git-bash or WSL.
