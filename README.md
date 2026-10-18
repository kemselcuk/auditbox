<img src="assets/icon.svg" width="64" height="64" alt="">

# AuditBox

Created in [T3 Code](https://t3.codes).

## Overview

AuditBox is a small event-history service: it will accept transaction/event history over HTTP,
store it in PostgreSQL, and serve it back with filters and stable keyset pagination. Target
behavior: ingestion is idempotent per `eventId` (Stage 3), listing uses keyset pagination
(Stage 4), and access is authenticated with a static `X-API-Key` (Stage 5). Today the skeleton,
schema, canonical hash, and write API are in place — see the status line below.
AuditBox is **not** a tamper-proof ledger — it records what it receives, and stored records can be
updated or deleted like any other database row.

Status: Stage 3 (event ingestion API) — roadmap in [docs/plan.md](docs/plan.md).

## Prerequisites

- Java 21 LTS (build target `maven.compiler.release=21`; verified with Temurin 21.0.8)
- Docker with the Compose plugin (`docker compose`)
- No secrets needed: every variable has a safe local default (see [.env.example](.env.example))

## Getting started

### 1. Start PostgreSQL

```bash
docker compose up -d db
```

`docker compose ps` then shows the container as healthy:

```text
NAME          IMAGE                COMMAND                  SERVICE   CREATED        STATUS                PORTS
auditbox-db   postgres:18-alpine   "docker-entrypoint.s…"   db        8 seconds ago  Up 8 seconds (healthy)   0.0.0.0:5433->5432/tcp, [::]:5433->5432/tcp
```

> The default host port is 5432 (`DB_PORT` in [.env.example](.env.example)). On the machine this
> output was produced on, 5432 is already used by a host PostgreSQL, so the run used `DB_PORT=5433`.
> If 5432 is free on yours, omit the variable and the port everywhere below.

### 2. Run the application (local profile)

```bash
DB_PORT=5433 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Expected startup on a second run (abridged real output; the first run on an empty volume also logs
`<< Empty Schema >>` and `Migrating schema "public" to version "1 - create events"`):

```text
... org.flywaydb.core.FlywayExecutor         : Database: jdbc:postgresql://localhost:5433/auditbox (PostgreSQL 18.6)
... o.f.core.internal.command.DbValidate     : Successfully validated 1 migration (execution time 00:00.014s)
... o.f.core.internal.command.DbMigrate      : Current version of schema "public": 1
... o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
... io.auditbox.AuditBoxApplication          : Started AuditBoxApplication in 2.348 seconds (process running for 2.542)
```

Flyway applies `V1__create_events.sql` on first start; later starts validate the migration and
report that the schema is up to date.

### 3. Check it is alive

```bash
curl http://localhost:8080/actuator/health
```

```text
{"groups":["liveness","readiness"],"status":"UP"}
```

`/actuator/health` is public; every other path requires authentication (Spring Security default)
until Stage 5 replaces it with X-API-Key authentication.

### 4. Stop

- Application: `Ctrl-C`
- Database, keeping data: `docker compose down`
- Database, deleting data: `docker compose down -v`

## API

Both endpoints live under `/api/v1/events`. Machine-readable OpenAPI is served at
`/v3/api-docs` and the UI at `/swagger-ui/index.html` (enabled in all profiles until Stage 5
restricts it). **Authentication today is the temporary
HTTP basic auth** of the `local`/`test` profiles (Stage 5 replaces it with a static
`X-API-Key` header — the examples below use the test-profile credentials; for a `local`
run, pass the user Spring Boot generates and logs at startup, or set
`SPRING_SECURITY_USER_NAME`/`SPRING_SECURITY_USER_PASSWORD`).

### Record an event

`POST /api/v1/events` — idempotent per `eventId` (D4):

- Body: JSON with `eventId` (client-generated, max 128 chars), `userId`, `eventType`,
  `source`, `occurredAt` (strict ISO-8601 with offset, within
  `0001-01-01T00:00:00Z..9999-12-31T23:59:59.999999Z`), optional `metadata` (small JSON).
- `201 Created` stores a new event; `200 OK` is an idempotent retry (same `eventId` with
  identical content — body is the stored record); `409 Conflict` is the same `eventId` with
  different content (stored record in the `existingEvent` extension); `400 Bad Request`
  covers validation failures (Problem Details, `errors` extension). `receivedAt` is set by
  the server.

```bash
curl -sS -u test-user:test-password http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42",
       "eventType":"payment.settled","source":"core-banking",
       "occurredAt":"2026-10-18T12:00:00Z"}'
```

```text
{"id":1,"eventId":"eb5a3a10-6d2d-4df1-9e3a-1e5a0b2c3d40","userId":"user-42",
 "eventType":"payment.settled","source":"core-banking","occurredAt":"2026-10-18T12:00:00Z",
 "metadata":null,"receivedAt":"2026-10-18T12:00:00.123456Z"}
```

### List events

`GET /api/v1/events` — filters and stable keyset pagination (D3):

- Filters (optional, exact match): `userId`, `eventType`, `source`; a blank value counts as
  no filter. `occurredFrom`/`occurredTo` are inclusive bounds, strict ISO-8601 with offset and
  inside the same `0001..9999` supported range as `occurredAt`.
- Pagination: newest first (`occurredAt DESC, eventId DESC`). Echo the previous response's
  `nextCursor` verbatim as `cursor`; `nextCursor` is only set when more results exist and is
  null on the last page. `size` is 1..100 (default 20). There is deliberately no total
  count; a page request is a single SELECT of at most `size + 1` rows.
- Errors: `400 Bad Request` Problem Details for a malformed cursor, a non-ISO-8601 or
  out-of-range bound, an inverted range or an out-of-range `size` — the detail names the
  offending parameter.

```bash
curl -sS -u test-user:test-password \
  'http://localhost:8080/api/v1/events?userId=user-42&size=10'
curl -sS -u test-user:test-password \
  'http://localhost:8080/api/v1/events?cursor=<nextCursor-from-the-previous-page>'
```

## Demo

Placeholder — filled in Stage 6 (sample-event client, single-page web demo, dedupe script).

## Testing

Placeholder — filled in Stage 7 with the test/evidence matrix. Today, `./mvnw verify` runs the
full unit and integration suite against a real PostgreSQL (Testcontainers,
`postgres:18-alpine`), including the write API, the idempotency/conflict paths and the
concurrency scenarios.

## Decisions

Placeholder — decision log with rationale: [docs/decisions.md](docs/decisions.md).
