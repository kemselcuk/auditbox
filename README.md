<img src="assets/icon.svg" width="64" height="64" alt="">

# AuditBox

A small Spring Boot service that accepts transaction/event history over HTTP, stores it in
PostgreSQL, and serves it back with filters and stable keyset pagination — written as a
provable reference implementation: every documented behavior is backed by a test or a real
transcript.

Created in [T3 Code](https://t3.codes). Roadmap and status: [docs/plan.md](docs/plan.md).

[![CI](https://github.com/kemselcuk/auditbox/actions/workflows/ci.yml/badge.svg)](https://github.com/kemselcuk/auditbox/actions/workflows/ci.yml)

## Overview

- **Ingestion** (`POST /api/v1/events`) is idempotent per client-generated `eventId`: an
  identical retry returns `200` with the stored record, different content on the same
  `eventId` is rejected with `409` — including under concurrency (20 identical parallel
  requests produce exactly one row).
- **Listing** (`GET /api/v1/events`) filters by `userId`, `eventType`, `source` and
  `occurredAt` range, orders newest first, and pages with an opaque keyset cursor. Each page
  request is a single `SELECT` capped at `size + 1` rows; there is no total count, and pages
  are stable under concurrent inserts.
- **Single fetch** (`GET /api/v1/events/{eventId}`) returns one stored record, or `404`
  Problem Details for an unknown `eventId`.
- **Metrics**: Micrometer counters for created/replayed/conflicted events and failed API-key
  checks, exposed through the key-protected `/actuator/metrics` endpoint.
- **Correlation**: every request gets an `X-Request-Id` (echoed from a safe incoming value or
  generated), carried in the response header and the MDC of every log line; `prod` logs ECS
  JSON to the console.
- **Authentication** is a static `X-API-Key` header checked with a constant-time comparison;
  `prod` refuses to start without a configured key.
- **OpenAPI** descriptions exist for all endpoints (`/v3/api-docs`), served only in the
  `dev`/`test` profiles.

The full behavior statement is [docs/plan.md](docs/plan.md); the rationale behind each choice
is [docs/decisions.md](docs/decisions.md) (D1–D15).

## What this is / is not

AuditBox **records what it receives**; nothing more is claimed about history:

- It is **not** a tamper-proof or immutable ledger — stored rows are ordinary PostgreSQL rows
  and can be updated or deleted like any other database row; the content hash documents
  content identity, and protects nothing.
- It is the only service in this repository (no event platform, no sidecar), and the
  PostgreSQL database is the only data store, with a single `events` table.
- There is no total-count API and no offset pagination (rejected alternatives, D3); no event
  platform such as Kafka and no sidecar service (scope: `AGENTS.md`, this plan).

## Architecture

One Spring Boot application, one PostgreSQL database, one `events` table:

```
            ┌────────────────────────────────────────────────────────────────┐
 client ───▶│ HTTP  :8080                                                    │
  (curl,    │                                                                │
  demo app, │   X-API-Key filter ──▶ EventController                         │
  browser)  │   (constant-time             │  /api/v1/events                  │
  page)     │    SHA-256/                  │                                  │
            │    MessageDigest)            ├─▶ POST: validation → canonical   │
            │                              │   content hash (SHA-256)         │
            │                              │   → REQUIRES_NEW insert          │
            │                              │     → unique-violation race      │
            │                              │       → 201 / 200 / 409          │
            │                              │                                  │
            │                              └─▶ GET: parse/validate query      │
            │                                  → keyset Specification query   │
            │                                    (one SELECT, size + 1 cap,   │
            │                                     opaque base64url cursor)    │
            │                                                                │
            │   Flyway ──▶ single migration V1: events table                 │
            │   springdoc (dev/test profiles only) ──▶ /v3/api-docs, /swagger-ui │
            └───────────────┬────────────────────────────────────────────────┘
                            │ JDBC
                            ▼
                PostgreSQL (container, host port 5432 or DB_PORT)
                single table `events`:
                  id (identity PK), event_id (unique, uq_events_event_id),
                  user_id, event_type, source, occurred_at TIMESTAMPTZ,
                  received_at TIMESTAMPTZ (server, default now()),
                  metadata JSONB NULL, content_hash TEXT,
                  created_at TIMESTAMPTZ (default now())
                  indexes: (occurred_at DESC, event_id DESC),
                           user_id, event_type, source
```

Components (source layout under [src/main/java/io/auditbox](src/main/java/io/auditbox)):

| Package | Role |
|---|---|
| `config` | Spring Security filter chain: `ApiKeyAuthenticationFilter`, constant-time `ApiKeyMatcher`, `ApiKeyProdRequirement` fail-fast guard, entry point returning Problem Details |
| `events` | `EventController` (POST/GET), `EventService`/`EventInsertService` (write path and idempotency race), `EventQueryService` (read path, keyset), `EventContentHasher` (canonical hash), `EventCursor` (opaque cursor codec), validators, Problem Details advice |
| `demo` | sample-event client (JDK only) |

The browser demo page is a static resource ([src/main/resources/static/demo](src/main/resources/static/demo/index.html))
served by the app itself.

## Getting started

Prerequisites: Java 21 LTS (build target `maven.compiler.release=21`; verified with
Temurin 21.0.8), Docker with the Compose plugin (`docker compose`). No secrets are needed —
every variable has a safe local default (see [.env.example](.env.example)).

### 1. Start the database

```bash
DB_PORT=5433 docker compose up -d db
```

`docker compose ps` then shows the container as healthy:

```text
NAME          IMAGE                COMMAND                  SERVICE   CREATED        STATUS                PORTS
auditbox-db   postgres:18-alpine   "docker-entrypoint.s…"   db        8 seconds ago  Up 8 seconds (healthy)   0.0.0.0:5433->5432/tcp, [::]:5433->5432/tcp
```

> The default host port is 5432 (`DB_PORT` in [.env.example](.env.example)). On the machine
> this output was produced on, 5432 was already used by a host PostgreSQL, so the run used
> `DB_PORT=5433`. If 5432 is free on yours, omit the variable and the port everywhere below.

### 2. Run the application (local profile)

```bash
DB_PORT=5433 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Expected startup on a second run (abridged real output; the first run on an empty volume also
logs `<< Empty Schema >>` and `Migrating schema "public" to version "1 - create events"`):

```text
... org.flywaydb.core.FlywayExecutor         : Database: jdbc:postgresql://localhost:5433/auditbox (PostgreSQL 18.6)
... o.f.core.internal.command.DbValidate     : Successfully validated 1 migration (execution time 00:00.014s)
... o.f.core.internal.command.DbMigrate      : Current version of schema "public": 1
... o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
... io.auditbox.AuditBoxApplication          : Started AuditBoxApplication in 2.348 seconds (process running for 2.542)
```

Flyway applies `V1__create_events.sql` on first start; later starts validate the migration and
report that the schema is up to date.

### 3. First request

```bash
curl http://localhost:8080/actuator/health
```

```text
{"groups":["liveness","readiness"],"status":"UP"}
```

`/actuator/health` is public (compose-style health checks), and so is the static demo page
under `/demo/**` (no secrets — see [Demo](#demo)). Every API path requires the static
`X-API-Key` header (see [Authentication](#authentication)). With the `local` profile the key
has the safe default `local-dev-key` — see [API](#api) below for the first POST.

### 4. Stop

- Application: `Ctrl-C`
- Database, keeping data: `docker compose down`
- Database, deleting data: `docker compose down -v`

### Full stack in one command (optional app container)

Instead of running the app on the host, you can start database + application together as
containers (the `app` service is opt-in behind the `app` compose profile; the default
`docker compose up -d db` workflow is unchanged):

```bash
DB_PORT=5433 docker compose --profile app up --build -d
```

`DB_PORT` only selects the database's **host** port (omit it when 5432 is free); the app
container always reaches the database at `db:5432` inside the compose network. This builds the
Docker image, starts `db` and waits for its health check before starting the app. When the app
is up:

```bash
curl http://localhost:8080/actuator/health   # {"groups":["liveness","readiness"],"status":"UP"}
```

The same `local` profile defaults apply inside the container: the database connection uses the
same `DB_NAME`/`DB_USER`/`DB_PASSWORD` defaults as the `db` service (`auditbox`, override via
`DB_HOST`/`DB_PORT`/`.env`), and the API key defaults to `local-dev-key` — override with
`API_KEY` in `.env` for anything reachable by others:

```bash
curl -sS -i -H "X-API-Key: local-dev-key" http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"...","userId":"user-42","eventType":"payment.settled",
       "source":"core-banking","occurredAt":"2026-10-18T12:00:00Z"}'
```

Stop the full stack, keeping data: `docker compose --profile app down`. Delete data as well:
`docker compose --profile app down -v`.

## API

Both endpoints live under `/api/v1/events` and require the API key. Machine-readable OpenAPI
is served at `/v3/api-docs`, the UI at `/swagger-ui/index.html` — both **disabled by default**
(D5); enable them with the dev profile, e.g.
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local,dev`. Documentation requires the API
key like every other non-public endpoint — the only public paths are `/actuator/health/**`
and the static demo page under `/demo/**`.

> A plain browser visit of the Swagger UI cannot attach the `X-API-Key` header, so the UI
> answers `401` and will not load. Open it through a header-capable client (a browser
> extension or a local proxy adding the header), or fetch the machine-readable document
> directly:
>
> ```bash
> curl -sS -H "X-API-Key: local-dev-key" http://localhost:8080/v3/api-docs
> ```

### Record an event (POST)

`POST /api/v1/events` — idempotent per `eventId` (D4). Body: `eventId` (client-generated, max
128 chars), `userId`, `eventType`, `source`, `occurredAt` (strict ISO-8601 with offset, within
`0001-01-01T00:00:00Z..9999-12-31T23:59:59.999999Z`), optional `metadata` (any JSON value).
`receivedAt` is set by the server.

```bash
curl -sS -i -H "X-API-Key: local-dev-key" http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42",
       "eventType":"payment.settled","source":"core-banking",
       "occurredAt":"2026-10-18T12:00:00Z"}'
```

Real response (new event):

```text
HTTP/1.1 201
{"id":761,"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42",
 "eventType":"payment.settled","source":"core-banking","occurredAt":"2026-10-18T12:00:00Z",
 "metadata":null,"receivedAt":"2026-10-18T21:40:17.857688Z"}
```

Status semantics (all bodies carry the stored/event record; the internal `content_hash` is
never exposed):

- `201 Created` — new event stored.
- `200 OK` — idempotent retry: same `eventId` with identical content (equivalent spellings
  hash identically, e.g. a different UTC offset, a different JSON key order, `100` vs `100.0`).
  The body is the stored record.
- `409 Conflict` — same `eventId` with different content. Problem Details with the stored
  record in the `existingEvent` extension; nothing is overwritten:

```text
HTTP/1.1 409
{"detail":"an event with eventId 'eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40' already exists with different content","instance":"/api/v1/events","status":409,"title":"Conflict","existingEvent":{"id":761,"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42","eventType":"payment.settled","source":"core-banking","occurredAt":"2026-10-18T12:00:00Z","metadata":null,"receivedAt":"2026-10-18T21:40:17.857688Z"}}
```

`400 Bad Request` — validation failures as Problem Details with an `errors` extension (one
`(field, message)` pair per violation, sorted by field):

```text
HTTP/1.1 400
{"detail":"Request validation failed","instance":"/api/v1/events","status":400,"title":"Bad Request","errors":[{"field":"eventId","message":"eventId must not be blank"},{"field":"occurredAt","message":"occurredAt must be a strict ISO-8601 date-time with a UTC offset (Z or +/-hh:mm) between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59.999999Z, e.g. 2026-10-18T12:00:00Z"},{"field":"userId","message":"userId must not be blank"}]}
```

Beyond Bean Validation, `400` also covers unreadable request parts: malformed JSON returns a
generic Problem Details detail (the `errors` extension is reserved for Bean Validation),
oversized or huge-exponent metadata numbers are bounded by a validator (`400` never `500`), and
NUL characters or numeric data PostgreSQL rejects (SQLState class 22) return a generic detail.
Unexpected errors are a generic `500` Problem Details without internals; the server logs them.

### List events (GET)

`GET /api/v1/events` — filters and stable keyset pagination (D3):

- Filters (optional, exact match): `userId`, `eventType`, `source`; a blank value counts as no
  filter. `occurredFrom`/`occurredTo` are inclusive bounds, strict ISO-8601 with offset, inside
  the same supported range as `occurredAt`.
- Pagination: newest first (`occurredAt DESC, eventId DESC`). Echo the previous response's
  `nextCursor` verbatim as `cursor`; `nextCursor` is only set when more results exist and is
  null on the last page. `size` is 1..100 (default 20). There is deliberately no total count;
  a page request is a single SELECT of at most `size + 1` rows.
- Errors: `400 Bad Request` Problem Details for a malformed cursor, a non-ISO-8601 or
  out-of-range bound, an inverted range or an out-of-range `size` — the detail names the
  offending parameter.

```bash
curl -sS -H "X-API-Key: local-dev-key" 'http://localhost:8080/api/v1/events?userId=user-42&size=10'
```

Real response:

```text
{"items":[{"id":761,"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42",
 "eventType":"payment.settled","source":"core-banking","occurredAt":"2026-10-18T12:00:00Z",
 "metadata":null,"receivedAt":"2026-10-18T21:40:17.857688Z"}],"nextCursor":null}
```

Next page:

```bash
curl -sS -H "X-API-Key: local-dev-key" \
  'http://localhost:8080/api/v1/events?cursor=<nextCursor-from-the-previous-page>'
```

### Fetch one event (GET)

`GET /api/v1/events/{eventId}` — the stored record of exactly the given `eventId`, in the
same shape as the `201` body of its POST and the `existingEvent` extension of its `409`:

```bash
curl -sS -H "X-API-Key: local-dev-key" \
  'http://localhost:8080/api/v1/events/eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40'
```

Real response (stored event):

```text
HTTP/1.1 200
{"id":761,"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42",
 "eventType":"payment.settled","source":"core-banking","occurredAt":"2026-10-18T12:00:00Z",
 "metadata":null,"receivedAt":"2026-10-18T21:40:17.857688Z"}
```

Unknown `eventId` → `404 Not Found` Problem Details with a generic detail (no internals):

```text
HTTP/1.1 404
{"type":"about:blank","title":"Not Found","status":404,"instance":"/api/v1/events/unknown-id",
 "detail":"Event not found"}
```

## Observability

- **Metrics** (Milestone 9.4): `GET /actuator/metrics` is exposed (health, info and metrics
  are exposed; key-protected like every non-health endpoint). Domain counters:
  `auditbox.events.created` (201), `auditbox.events.replayed` (200), `auditbox.events.conflicts`
  (409) and `auditbox.auth.failures` with the closed tag set `reason=missing|invalid` — the
  key itself is never a metric name, tag, or value.

```bash
curl -sS -H "X-API-Key: local-dev-key" http://localhost:8080/actuator/metrics/auditbox.events.created
```

```text
{"name":"auditbox.events.created","description":null,"baseUnit":null,
 "measurements":[{"statistic":"COUNT","value":1.0}],"availableTags":[]}
```

- **Correlation IDs** (Milestone 9.5): send `X-Request-Id` (1..128 characters of
  `A-Za-z0-9._-`) and the service echoes it verbatim in the response header — 401s included —
  and puts it under the `requestId` MDC key for all its log lines. When the header is absent
  or unsafe, a UUID is generated; an untrusted value is never echoed:

```text
$ curl -sS -i -H "X-API-Key: local-dev-key" -H "X-Request-Id: run-42" http://localhost:8080/api/v1/events
HTTP/1.1 200
X-Request-Id: run-42
```

- **Structured logging** (Milestone 9.5, `prod` profile only): the console logs are ECS JSON
  (`logging.structured.format.console=ecs`, a Spring Boot built-in — no extra dependency);
  local/dev/test keep the human-readable console format.

## Authentication

Every request except `/actuator/health/**` and the static demo page (`/demo/**`, see
[Demo](#demo)) must carry the static API key in the `X-API-Key` header (decision
[D5](docs/decisions.md)): a stateless Spring Security filter compares it against the
configured key with a constant-time comparison (SHA-256 of both values,
`MessageDigest.isEqual` — a code-level property, D14).

- Configuration: `auditbox.security.api-key`, bound from the `API_KEY` environment
  variable (see [.env.example](.env.example)).
- `local`/`dev` profiles: safe development default `local-dev-key` always works locally;
  override with `API_KEY` for anything reachable by others.
- `test` profile: `test-api-key`.
- `prod` profile: fails fast at startup when `API_KEY` is missing or blank
  (`IllegalStateException`), so a misconfigured deployment never serves traffic.
- Missing or wrong key → `401 Unauthorized` as Problem Details (`application/problem+json`),
  plus `WWW-Authenticate: X-API-Key`; the body never echoes a submitted key and the key is
  never logged:

```text
HTTP/1.1 401
{"type":"about:blank","title":"Unauthorized","status":401,
 "detail":"Missing or invalid X-API-Key header. Send the static API key in the X-API-Key header."}
```

- Exactly one `X-API-Key` header is expected per request; if a proxy or client emits the
  header twice, the first value wins under the current servlet-container behavior.

## Demo

Three ways to exercise the behavior on a running stack. Start it first (same commands as
[Getting started](#getting-started)):

```bash
DB_PORT=5433 docker compose up -d db   # omit DB_PORT when 5432 is free
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### 1. Sample-event client (T6.1)

A small standalone Java client using only the JDK (`java.net.http.HttpClient`, hand-built
JSON — no extra dependencies). Flags: `--base-url` (default `http://localhost:8080`),
`--api-key` (default `local-dev-key`), `--count` (default 20), `--batch` (default: a UTC
timestamp; eventIds are `demo-<batch>-<i>`). Re-running with the same `--batch` replays
identical content, so the second run comes back wrapped with idempotent `200`s (D4):

```bash
./mvnw compile
java -cp target/classes io.auditbox.demo.SampleEventClient --count=6 --batch=run-1
java -cp target/classes io.auditbox.demo.SampleEventClient --count=6 --batch=run-1
```

Real output of the first (fresh) run and the second (same batch) run:

```text
AuditBox sample-event client
  base-url : http://localhost:8080
  batch    : run-1
  count    : 6

  [201] demo-run-1-0
  [201] demo-run-1-1
  [201] demo-run-1-2
  [201] demo-run-1-3
  [201] demo-run-1-4
  [201] demo-run-1-5

Summary
  events sent : 6 of 6
  HTTP 201: 6
  eventIds    : demo-run-1-0, demo-run-1-1, demo-run-1-2, demo-run-1-3, demo-run-1-4, demo-run-1-5
```

```text
AuditBox sample-event client
  base-url : http://localhost:8080
  batch    : run-1
  count    : 6

  [200] demo-run-1-0
  [200] demo-run-1-1
  [200] demo-run-1-2
  [200] demo-run-1-3
  [200] demo-run-1-4
  [200] demo-run-1-5

Summary
  events sent : 6 of 6
  HTTP 200: 6
  eventIds    : demo-run-1-0, demo-run-1-1, demo-run-1-2, demo-run-1-3, demo-run-1-4, demo-run-1-5
```

The second run is the idempotent replay (same eventIds, identical content → `200`, D4). The
client exits `0` when every request got a 2xx, `1` when a connection failed or any status
≥ 400 came back, and `2` on usage errors; an invalid `--base-url` (not an absolute
http/https URL) is rejected up front as a usage error, and trailing slashes are stripped so
request paths never double the slash.

### 2. Browser demo (T6.2)

Open <http://localhost:8080/demo/index.html> in a browser. The page itself is public (no key
needed to load it); paste the API key into the key input and every API call from the page
carries it as the `X-API-Key` header. The page offers filter fields (`userId`, `eventType`,
`source`, `occurredFrom`, `occurredTo`, `size`), a "Send sample event" form, a results table
with `id`/`eventId`/`userId`/`eventType`/`source`/`occurredAt`/`receivedAt`/`metadata` and a
"Load more" button walking the keyset cursor. Errors render as the RFC 9457 Problem Details
they really are (401 without a key, 409 conflicts with the `existingEvent` extension, 400
field errors).

![AuditBox demo page: API key input, filters, a table of loaded events and the nextCursor badge](docs/images/demo.png)

The screenshot is a real run (headless Chrome against the local profile): the key was pasted,
"Load first page" fetched the newest 20 events and the page reports `nextCursor available`.
The screenshot's key field shows the documented local development default (`local-dev-key`),
not a secret.

### 3. Dedupe script (T6.3)

One command reproducing the D4 concurrency behavior end to end — N parallel identical POSTs
(default 10: exactly one `201`, the rest `200`, exactly one DB row), an identical retry
(`200`), different content on the same eventId (`409` with the `existingEvent` extension) and
a fresh read page showing the single row. Exits non-zero on any failed expectation; every run
uses a fresh, per-process eventId, so back-to-back and even overlapping reruns stay valid.
Configure with `BASE_URL`, `API_KEY`, `DB_PORT` (the fallback host port used only when docker
is unavailable), `CONCURRENCY`, `DB_CONTAINER` (container for the row-count psql call) and,
for that fallback, `DB_NAME`/`DB_USER`/`DB_PASSWORD`:

```bash
./scripts/dedupe-demo.sh
```

```text
AuditBox dedupe demo
  BASE_URL    : http://localhost:8080
  API key     : configured (not echoed back)
  DB container: auditbox-db (DB_PORT 5432 = fallback host port without docker)
  Concurrency : 10
  eventId     : dedupe-demo-1792358673-73124   userId: dedupe-demo-1792358673-73124-user   occurredAt: 2026-10-18T21:24:33Z

== Scenario 1: 10 parallel identical POSTs for the fresh eventId ==
  status distribution: 1x201 (created) + 9x200 (idempotent) + 0 unexpected
  rows in 'events' for the eventId: 1 (expected 1)
  [PASS] one writer, N identical retries: only one row and exactly one 201

== Scenario 2: identical retry of the same payload ==
  status: 200 (expected 200)
  [PASS] same eventId + identical content -> idempotent 200

== Scenario 3: same eventId with different content ==
  status: 409 (expected 409)
  response body contains the existingEvent extension:
{"detail":"an event with eventId 'dedupe-demo-1792358673-73124' already exists with different content",
"instance":"/api/v1/events","status":409,"title":"Conflict","existingEvent":{"id":622,
"eventId":"dedupe-demo-1792358673-73124","userId":"dedupe-demo-1792358673-73124-user",
"eventType":"payment.settled","source":"dedupe-demo","occurredAt":"2026-10-18T21:24:33Z",
"metadata":null,"receivedAt":"2026-10-18T21:24:33.573705Z"}}

  rows in 'events' for the eventId after the conflict: 1 (expected 1)
  [PASS] first writer wins; different content is a 409, not an overwrite

== Scenario 4: fresh read page for the demo user ==
  status: 200 (expected 200)
  read page contains the eventId 1 time(s) (expected exactly 1)
  [PASS] the page returns exactly the one stored row and no cursor

ALL CHECKS PASSED (4 scenarios): idempotency behavior (D4) reproduced end to end
```

(The transcript above is a real run with two long JSON lines re-wrapped for readability; your
own run reproduces the same shape with a fresh eventId.)

## Testing and evidence

Run the whole suite (requires Docker for Testcontainers; no pre-started database needed):

```bash
./mvnw verify
```

The suite (JUnit 5; the integration classes use Testcontainers against the pinned
`postgres:18-alpine` image and real HTTP on a random port, the rest are pure unit tests) covers,
per class:

| Test class | Covers |
|---|---|
| `AuditBoxApplicationTests` | app boots against real PostgreSQL; `/actuator/health` UP (T1.1) |
| `EventsSchemaMigrationTest` | Flyway V1 applies on a clean database; columns, types, nullability, defaults, unique constraint (rejecting SQLState 23505), indexes (T2.1) |
| `EventContentHasherTest` | canonical hash rules: key order, UTC/microsecond normalization, number equality, absent ≡ null, SHA-256/UTF-8, thread safety (T2.2) |
| `EventApiControllerTest` | POST: 201/200/409 semantics, validation 400s with field errors, malformed-JSON generic detail, OpenAPI (including the `{eventId}` fetch), 20 concurrent identical requests → one row, different-content race, metadata number bounds and exact jsonb precision (T3.1/T3.2, D10/D12) |
| `EventListApiControllerTest` | GET: filters, keyset walks without gaps/duplicates (incl. tie groups), stability under inserts, cursor decode positives/negatives, default 20/max 100, one SELECT per page without a COUNT **and** the SQL LIMIT clause, OpenAPI, parameter-naming 400s, raw percent-escape 400 (T4.1/T4.2, D13) |
| `MetricsApiControllerTest` | Micrometer counters over real HTTP: created/replayed/conflict deltas for 201/200/409, auth-failure `reason=missing|invalid` tags (key never tagged), metrics endpoint 401 without / 200 with the key (milestone 9.4) |
| `RequestIdFilterTest` | correlation filter: safe-pattern decision boundaries (echo verbatim vs. generated UUID, length 1/128/129), unsafe-id scrubbing over real HTTP, echo on 401 responses (milestone 9.5) |
| `EventCursorTest` | cursor codec unit tests: round trips and every strict rejection (T4.1/T4.2) |
| `ValidMetadataValidatorTest` | metadata number bounds: canonical and stored form boundaries (D10/D12; rejection is allocation-free by design — a code-level property, not a timing test) |
| `ApiKeySecurityTest` | 401/401/201 pattern, key echo, duplicate-header first-value, health/actuator/docs scoping, 404/405 not masked, `/logout`, OpenAPI security scheme (T5.1) |
| `ApiKeyMatcherTest` | matcher unit rules: equality, null/blank, whitespace handling (T5.1; constant-time is a code-level property, D14) |
| `ApiKeyProdRequirementTest` | prod fail-fast: missing/blank/whitespace key, success with a key (T5.1) |
| `SampleEventClientTest` | demo client: config parsing, base-url validation, exit codes, eventId scheme, payload determinism (T6.1) |
| `DemoStaticPageTest` | demo page is public, contains the expected elements, embeds no secrets; the events API stays key-protected (T6.2) |

Which acceptance criterion each test proves: the **acceptance evidence matrix in
[docs/plan.md](docs/plan.md)** (T7.1) maps every criterion of stages 0–6 to its exact
artefact, including the evidence that is deliberately live/manual (compose walk-through,
browser demo, dedupe transcript) and the recorded evidence gaps.

The demo transcripts and the screenshot above are real runs, not mock-ups; API responses in
this README were captured from a live local run. Continuous integration runs the same suite
on every push/PR to `main` (badge at the top of this document). Every CI run always uploads
the CycloneDX SBOM of the build as the `sbom` artifact: the
[cyclonedx-maven-plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin) writes
`target/bom.json` (CycloneDX v1.6 JSON) on every `package`/`verify` (Milestone 9.6).

## Decisions

Every design decision with context, decision and rationale is in
[docs/decisions.md](docs/decisions.md) (D1–D15), owner-approved unless a review round changed
it. Short summary:

- **D1** event contract: `eventId`/`userId`/`eventType`/`source`/`occurredAt` required,
  optional metadata JSON, server adds `receivedAt`.
- **D2** filters: exact match on user/type/source plus `occurredAt` range; no free-text search.
- **D3** keyset pagination `(occurred_at DESC, event_id DESC)` with an opaque cursor; no total
  count; offset pagination rejected.
- **D4** idempotency: unique `event_id` + canonical content hash; identical retry → 200 with
  the stored record, different content → 409; races resolved from the unique violation.
- **D5** auth: static `X-API-Key`, constant-time check, health and the static demo page public,
  prod fail-fast, Swagger dev-profile only.
- **D6–D8** documentation structure (few central docs, no per-task files), repository language
  (English), demo scope (no video).
- **D9** stack baseline: Java 21 LTS, Spring Boot 4.1.1, Maven wrapper, Flyway 12.4,
  Testcontainers 2.0.5, springdoc 3.1.1, `postgres:18-alpine`; Jackson 3 (plus Jackson 2
  restrictions) note.
- **D10** canonical content hash rules: SHA-256, sorted keys, UTC microsecond truncation,
  exact decimals with bounded digits, absent ≡ null metadata.
- **D11** events schema v1: one table, named constraints and indexes, `received_at` vs
  `created_at` intent.
- **D12** write API contract and idempotency mechanics: status codes, Problem Details shapes,
  validation bounds, `REQUIRES_NEW` insert, SQLState 23505 handling.
- **D13** list API contract: filters, bounds, cursor codec and its size bound, one-SELECT
  query mechanics, error behavior.
- **D14** security chain details: filtering scheme, comparison, response shapes, docs gating.
- **D15** demo package: client, browser page, dedupe script and their reproducibility rules.
- **D16** post-handoff M9: CI + container, single fetch, metrics counters, correlation ID +
  prod ECS logs, SBOM.

## Next steps

- **Post-handoff M9 (done):** CI pipeline, container image + compose app profile,
  `GET /events/{eventId}`, Micrometer counters, `X-Request-Id` correlation and prod ECS logs,
  a CycloneDX SBOM (D16) and the MIT license (D17).
- **Playwright E2E (approved):** browser-level tests for the demo page behind a separate
  `-Pe2e` Maven profile (M9.7), so `./mvnw verify` keeps its current runtime.
- **M10 hardening (planned):** per-source hashed keys with rotation, rate limiting with
  429/`Retry-After`, dependency automation (Renovate/Dependabot).
- **Deferred by design** (recorded in decisions, revisit only with real need): composite
  filter indexes once real query patterns justify them (D11), importer support for
  overriding `received_at` to preserve original receipt times (D11), anything the passed
  plans and reviews rejected as scope creep.

An event platform (e.g. Kafka), additional services, and video demos remain out of scope.

## License

MIT — see [LICENSE](LICENSE) (decision D17).
