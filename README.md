<img src="assets/icon.svg" width="64" height="64" alt="">

# AuditBox

Created in [T3 Code](https://t3.codes).

## Overview

AuditBox is a small event-history service: it will accept transaction/event history over HTTP,
store it in PostgreSQL, and serve it back with filters and stable keyset pagination. Target
behavior: ingestion is idempotent per `eventId` (Stage 3), listing uses keyset pagination
(Stage 4), and access is authenticated with a static `X-API-Key` (Stage 5). Today the skeleton,
schema, and canonical hash are in place — see the status line below.
AuditBox is **not** a tamper-proof ledger — it records what it receives, and stored records can be
updated or deleted like any other database row.

Status: Stage 2 (schema and canonical content hash) — roadmap in [docs/plan.md](docs/plan.md).

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

Placeholder — filled in Stages 3–5 (`POST /api/v1/events`, `GET /api/v1/events`, X-API-Key auth,
OpenAPI/Swagger).

## Demo

Placeholder — filled in Stage 6 (sample-event client, single-page web demo, dedupe script).

## Testing

Placeholder — filled in Stage 7 with the test/evidence matrix. Today, `./mvnw verify` runs 14
tests: the health integration test (Testcontainers PostgreSQL `postgres:18-alpine`), 8
canonical-content-hash unit tests, and 5 schema-migration assertions.

## Decisions

Placeholder — decision log with rationale: [docs/decisions.md](docs/decisions.md).
