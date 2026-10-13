#!/usr/bin/env bash
# End-to-end smoke test: builds the image, starts api + 3 workers + postgres + redis,
# submits a batch of jobs and checks they all complete with correct results.
# Works on Linux CI and git-bash; needs docker, curl, sha256sum.
#
# Tunables (env): JOB_COUNT (30), TIMEOUT_SECONDS (180), API_PORT, POSTGRES_PORT, REDIS_PORT
set -euo pipefail

cd "$(dirname "$0")/.."

JOB_COUNT="${JOB_COUNT:-30}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"
# Dedicated project name and unusual host ports so a running dev stack
# (and its data volume) is neither touched nor conflicting.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-jobqueue-smoke}"
export API_PORT="${API_PORT:-18080}"
export POSTGRES_PORT="${POSTGRES_PORT:-15432}"
export REDIS_PORT="${REDIS_PORT:-16379}"
BASE="http://localhost:${API_PORT}"
COMPOSE=(docker compose --profile app)

ok=0
cleanup() {
  if [ "$ok" -ne 1 ]; then
    echo "=== smoke test FAILED; container state and recent logs ===" >&2
    "${COMPOSE[@]}" ps -a >&2 || true
    "${COMPOSE[@]}" logs --tail=60 >&2 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
json_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }

echo "==> starting stack (api + 3 workers)"
"${COMPOSE[@]}" up -d --build --scale worker=3

echo "==> waiting for $BASE/actuator/health"
deadline=$((SECONDS + TIMEOUT_SECONDS))
until curl -fsS "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  [ "$SECONDS" -lt "$deadline" ] || fail "API not healthy within ${TIMEOUT_SECONDS}s"
  sleep 2
done

# Workers start only after the api is healthy, so give them a moment to appear.
until [ "$("${COMPOSE[@]}" ps --status running --format '{{.Service}}' | grep -c '^worker$' || true)" -eq 3 ]; do
  [ "$SECONDS" -lt "$deadline" ] || fail "3 worker containers not running within ${TIMEOUT_SECONDS}s"
  sleep 2
done

echo "==> submitting $JOB_COUNT jobs"
ids=()
kinds=()
texts=()
for i in $(seq 1 "$JOB_COUNT"); do
  case $((i % 3)) in
    0) kind=ECHO;     payload="{\"n\":$i}" ;;
    1) kind=CHECKSUM; payload="{\"text\":\"smoke-$i\"}" ;;
    2) kind=SLEEP;    payload="{\"millis\":$((200 + (i % 5) * 100))}" ;;
  esac
  resp=$(curl -fsS -X POST "$BASE/api/v1/jobs" -H 'Content-Type: application/json' \
         -d "{\"type\":\"$kind\",\"payload\":$payload}") || fail "submit #$i ($kind) rejected"
  id=$(printf '%s' "$resp" | json_field id)
  [ -n "$id" ] || fail "no id in response for #$i: $resp"
  ids+=("$id"); kinds+=("$kind"); texts+=("smoke-$i")
done

echo "==> waiting for all jobs to reach COMPLETED"
for idx in "${!ids[@]}"; do
  id="${ids[$idx]}"
  while :; do
    body=$(curl -fsS "$BASE/api/v1/jobs/$id")
    status=$(printf '%s' "$body" | json_field status)
    [ "$status" = "COMPLETED" ] && break
    [ "$status" != "FAILED" ] || fail "job $id FAILED: $body"
    [ "$SECONDS" -lt "$deadline" ] || fail "timeout: job $id still $status"
    sleep 1
  done

  case "${kinds[$idx]}" in
    CHECKSUM)
      expected=$(printf '%s' "${texts[$idx]}" | sha256sum | cut -d' ' -f1)
      printf '%s' "$body" | grep -q "\"sha256\":\"$expected\"" \
        || fail "job $id: wrong checksum, want $expected, body: $body" ;;
    ECHO)
      printf '%s' "$body" | grep -q '"echo":{"n":' || fail "job $id: bad echo result: $body" ;;
    SLEEP)
      printf '%s' "$body" | grep -q '"sleptMillis":' || fail "job $id: bad sleep result: $body" ;;
  esac
done

echo "==> verifying nothing is left unfinished"
for s in PENDING QUEUED PROCESSING FAILED; do
  total=$(curl -fsS "$BASE/api/v1/jobs?status=$s&size=1" | sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p')
  [ "$total" = "0" ] || fail "$total job(s) in status $s"
done

ok=1
echo "OK: $JOB_COUNT jobs completed with correct results across 3 workers"
