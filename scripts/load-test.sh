#!/usr/bin/env bash
#
# AuditBox load test (plan task T11.3, decision D23): thin wrapper around the JDK-only
# load generator io.auditbox.demo.LoadGenerator. It runs against an ALREADY RUNNING local
# stack and never starts or stops services itself.
#
# IMPORTANT (D19): the app's per-source rate limiter defaults to 300 requests/min and would
# 429 a serious run. Start the app with a raised limit, e.g.
#   RATE_LIMIT_REQUESTS_PER_MINUTE=1000000 DB_PORT=5433 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# (see the README section "Load test").
#
# Configuration (environment variables, all optional):
#   BASE_URL         base URL of the running AuditBox    (default http://localhost:8080)
#   API_KEY          value of the X-API-Key header       (default local-dev-key)
#   REQUESTS         unique events to POST               (default 2000)
#   CONCURRENCY      max in-flight requests              (default 16)
#   REPLAY           1 = send every event a second time  (default 0)
#   RUN_ID           eventIds are load-<run-id>-<n>      (default: epoch-second-pid)
#   TIMEOUT_SECONDS  per-request timeout in seconds      (default 10)
#
# Exit status: the generator's — 0 when every request got 2xx, 1 when at least one
# connection failed or any response status was >= 400, 2 on usage errors.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-local-dev-key}"
REQUESTS="${REQUESTS:-2000}"
CONCURRENCY="${CONCURRENCY:-16}"
REPLAY="${REPLAY:-0}"
RUN_ID="${RUN_ID:-}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-10}"

# Preflight: the app must be reachable (health is public, no key needed) — same pattern as
# scripts/dedupe-demo.sh. The load run itself always sends the API key (never bypassed).
health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || true)"
[ "$health" = "200" ] || {
  printf '[FAIL] the app is not reachable at %s (health returned '"'%s'"')\n' "$BASE_URL" "${health:-no response}" >&2
  exit 1
}

# Always run the incremental compile: the previous "only if the class is missing" check
# ran stale classes after a source edit (review F13). Maven's incremental build is quick.
# (The API key is passed on the java command line; it is the documented dev/CI key here —
# for a real secret prefer the environment and a dedicated wrapper.)
./mvnw -q compile

args=(--base-url="$BASE_URL" --api-key="$API_KEY" --requests="$REQUESTS" \
      --concurrency="$CONCURRENCY" --timeout-seconds="$TIMEOUT_SECONDS")
if [ "$REPLAY" = "1" ]; then
  args+=(--replay)
fi
if [ -n "$RUN_ID" ]; then
  args+=(--run-id="$RUN_ID")
fi

# exec propagates the generator's exit code verbatim (fails on generator error).
exec java -cp target/classes io.auditbox.demo.LoadGenerator "${args[@]}"
