#!/usr/bin/env bash
#
# AuditBox demo: deterministic dedupe evidence (plan task T6.3, decision D4).
#
# What it proves against a locally running stack (docker compose up -d db +
# ./mvnw spring-boot:run -Dspring-boot.run.profiles=local):
#   1. N parallel identical POSTs for one fresh eventId  -> exactly ONE 201, the rest 200,
#      and exactly ONE database row for that eventId.
#   2. An identical retry of the same payload            -> 200 (idempotent).
#   3. The same eventId with different content           -> 409 Problem Details with the
#      "existingEvent" extension, and still exactly one row.
#   4. A fresh read page filtered on the demo user       -> 200 with the single row and
#      nextCursor:null (nothing else was written for it).
#
# The script is deterministic and repeatable: every run generates one fresh eventId (so the
# scenario-1 expectations hold on every run) and prints a stable transcript suitable for
# pasting into the README. It exits non-zero as soon as any expectation fails.
#
# Configuration (environment variables, all optional):
#   BASE_URL      base URL of the running AuditBox            (default http://localhost:8080)
#   API_KEY       value of the X-API-Key header               (default local-dev-key)
#   DB_PORT       host port of the compose PostgreSQL         (default 5432)
#   CONCURRENCY   number of parallel identical POSTs          (default 10)
#   DB_CONTAINER  container used for the row-count psql call  (default auditbox-db)
#
# Row counts use docker exec against the compose database (container-internal connection,
# so DB_PORT does not matter there); DB_PORT is only used by the fallback when docker is
# unavailable, which then relies on a host `psql` binary.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-local-dev-key}"
DB_PORT="${DB_PORT:-5432}"
CONCURRENCY="${CONCURRENCY:-10}"
DB_CONTAINER="${DB_CONTAINER:-auditbox-db}"
DB_NAME="${DB_NAME:-auditbox}"
DB_USER="${DB_USER:-auditbox}"

# Fresh, run-unique identifiers: expectation 201-once would fail after a rerun with a fixed id.
RUN_ID="dedupe-demo-$(date +%s)"
EVENT_ID="${RUN_ID}"
USER_ID="${RUN_ID}-user"
RUN_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

say()  { printf '%s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*" >&2; exit 1; }

# curl helpers -----------------------------------------------------------------

post_json() { # $1 = JSON body; prints the HTTP status, response body goes to stdout.body
  curl -sS -o "$WORK_DIR/stdout.body" -w '%{http_code}' \
    "$BASE_URL/api/v1/events" \
    -H "X-API-Key: $API_KEY" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

get_json() { # $1 = path with query; prints the HTTP status, body to stdout.body
  curl -sS -o "$WORK_DIR/stdout.body" -w '%{http_code}' \
    "$BASE_URL$1" -H "X-API-Key: $API_KEY"
}

build_payload() { # $1 = eventId, $2 = userId (identical everything else across scenarios)
  printf '{"eventId":"%s","userId":"%s","eventType":"payment.settled","source":"dedupe-demo","occurredAt":"%s"}' \
    "$1" "$2" "$RUN_TS"
}

# DB row count -----------------------------------------------------------------

db_event_count() {
  local sql="SELECT count(*) FROM events WHERE event_id = '$1';"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$DB_CONTAINER"; then
    docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$sql"
  else
    # Fallback without docker: a host psql binary connecting over the host port.
    if command -v psql >/dev/null 2>&1; then
      PGPASSWORD="${DB_PASSWORD:-auditbox}" psql -h localhost -p "$DB_PORT" -U "$DB_USER" \
        -d "$DB_NAME" -tAc "$sql"
    else
      fail "neither docker with container '$DB_CONTAINER' nor a host psql binary is available"
    fi
  fi
}

# Preflight: the app must be reachable (health is public, no key needed).
health="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")"
[ "$health" = "200" ] || fail "the app is not reachable at $BASE_URL (health returned $health)"

say "AuditBox dedupe demo"
say "  BASE_URL    : $BASE_URL"
say "  API key     : set (${#API_KEY} characters, not echoed back)"
say "  DB container: $DB_CONTAINER (DB host port: $DB_PORT)"
say "  Concurrency : $CONCURRENCY"
say "  eventId     : $EVENT_ID   userId: $USER_ID   occurredAt: $RUN_TS"
say ""

# --- Scenario 1: N parallel identical POSTs -> 1x201 + (N-1)x200 and one DB row ---
say "== Scenario 1: $CONCURRENCY parallel identical POSTs for the fresh eventId =="
codes_dir="$WORK_DIR/codes"
mkdir "$codes_dir"
for i in $(seq 1 "$CONCURRENCY"); do printf '000' > "$codes_dir/$i"; done
for i in $(seq 1 "$CONCURRENCY"); do
  ( status="$(post_json "$(build_payload "$EVENT_ID" "$USER_ID")")" && printf '%s' "$status" > "$codes_dir/$i" ) &
done
wait

c_201=0; c_200=0; c_unexpected=0; unexpected=""
for i in $(seq 1 "$CONCURRENCY"); do
  status="$(cat "$codes_dir/$i")"
  case "$status" in
    201) c_201=$((c_201 + 1)) ;;
    200) c_200=$((c_200 + 1)) ;;
    *)   c_unexpected=$((c_unexpected + 1)); unexpected="$unexpected ($status)" ;;
  esac
done
say "  status distribution: ${c_201}x201 (created) + ${c_200}x200 (idempotent) + ${c_unexpected} unexpected${unexpected:+:$unexpected}"
[ "$c_201" = "1" ] || fail "scenario 1: expected exactly 1 response with 201, got $c_201"
[ "$c_200" = "$((CONCURRENCY - 1))" ] || fail "scenario 1: expected $((CONCURRENCY - 1)) responses with 200, got $c_200"
[ "$c_unexpected" = "0" ] || fail "scenario 1: expected no unexpected responses, got$c_unexpected"

rows="$(db_event_count "$EVENT_ID" | tr -d '[:space:]')"
say "  rows in 'events' for the eventId: $rows (expected 1)"
[ "$rows" = "1" ] || fail "scenario 1: expected exactly 1 row for eventId $EVENT_ID, got $rows"
say "  [PASS] one writer, N identical retries: only one row and exactly one 201"
say ""

# --- Scenario 2: identical retry -> 200 ----------------------------------------
say "== Scenario 2: identical retry of the same payload =="
status="$(post_json "$(build_payload "$EVENT_ID" "$USER_ID")")"
say "  status: $status (expected 200)"
[ "$status" = "200" ] || fail "scenario 2: expected 200 for the identical retry, got $status"
say "  [PASS] same eventId + identical content -> idempotent 200"
say ""

# --- Scenario 3: same eventId, different content -> 409 with existingEvent ------
say "== Scenario 3: same eventId with different content =="
conflict_payload="$(build_payload "$EVENT_ID" "$USER_ID-different")"
status="$(post_json "$conflict_payload")"
body="$(cat "$WORK_DIR/stdout.body")"
say "  status: $status (expected 409)"
[ "$status" = "409" ] || fail "scenario 3: expected 409 for different content, got $status"
printf '%s\n' "$body" > "$WORK_DIR/conflict.json"
say "  response body contains the existingEvent extension:"
say "$body" | head -c 600
say ""
grep -q '"existingEvent"' "$WORK_DIR/conflict.json" || fail "scenario 3: 409 body lacks the existingEvent extension"
rows="$(db_event_count "$EVENT_ID" | tr -d '[:space:]')"
say "  rows in 'events' for the eventId after the conflict: $rows (expected 1)"
[ "$rows" = "1" ] || fail "scenario 3: the conflicting submission stored a row (found $rows)"
say "  [PASS] first writer wins; different content is a 409, not an overwrite"
say ""

# --- Scenario 4: fresh read page shows the single row ---------------------------
say "== Scenario 4: fresh read page for the demo user =="
status="$(get_json "/api/v1/events?userId=$USER_ID&size=10")"
body="$(cat "$WORK_DIR/stdout.body")"
say "  status: $status (expected 200)"
[ "$status" = "200" ] || fail "scenario 4: expected 200 for the read page, got $status"
occurrences="$(printf '%s' "$body" | grep -o "\"$EVENT_ID\"" | wc -l | tr -d '[:space:]')"
say "  read page contains the eventId $occurrences time(s) (expected exactly 1)"
[ "$occurrences" = "1" ] || fail "scenario 4: expected the demo row exactly once on the page, got $occurrences"
printf '%s' "$body" | grep -q '"nextCursor":null' \
  || fail "scenario 4: expected nextCursor:null on the single-row page"
say "  [PASS] the page returns exactly the one stored row and no cursor"
say ""

say "ALL CHECKS PASSED (4 scenarios): idempotency behavior (D4) reproduced end to end"
