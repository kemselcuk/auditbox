# Decision Log

Decisions made during planning on 2026-10-18, owner-approved unless noted. Format: context →
decision → rationale/alternatives.

## D1 — Event contract

- Context: HTTP ingestion of transaction events; scope must stay small and provable.
- Decision: Required fields `eventId` (client-generated, UUID recommended), `userId`, `eventType`,
  `source`, `occurredAt` (UTC ISO-8601). Optional `metadata` (small key-value JSON). Server adds
  `receivedAt`.
- Rationale: Minimal core keeps validation tight; metadata covers extension without schema churn.
  Rejected: mandatory amount/currency (not core to the exercise), free-form payload (weakens
  validation and indexing).

## D2 — Listing filters

- Decision: `userId`, `eventType`, `source`, `occurredAt` from/to.
- Rationale: Common query patterns, indexable with plain B-trees. Free-text search rejected (scope).

## D3 — Pagination

- Decision: Keyset (cursor) pagination ordered by `occurred_at DESC, event_id DESC`; opaque base64
  cursor; default page size 20, max 100; no total count.
- Rationale: Stable under concurrent inserts (no offset drift); total counts add cost without a
  consumer. Offset pagination documented as the rejected alternative.

## D4 — Duplicate submissions (idempotency)

- Decision: Unique constraint on `event_id` plus a content hash. Same eventId + identical content →
  200 with the stored record (idempotent success). Same eventId + different content → 409 Conflict
  with the stored record summary. New record → 201. Races are resolved by catching the
  unique-violation, re-reading the row, and comparing hashes.
- Rationale: First-writer-wins with explicit conflict signalling; no silent overwrites.
  Rejected: always-409 (hostile to retries), silent ignore (undebuggable).

## D5 — Authentication

- Decision: Static API key via `X-API-Key` header, read from the environment; Spring Security filter
  with constant-time comparison; missing/wrong key → 401. Health endpoint public (compose
  healthcheck). Swagger UI enabled only in the dev profile. `prod` profile fails fast if no key is
  configured.
- Rationale: Simplest credible service-to-service auth for this scope. Per-source keys and HTTP
  Basic noted as alternatives, rejected for now.

## D6 — Documentation structure

- Decision: Few central documents: `AGENTS.md`, `README.md`, `docs/plan.md`, `docs/decisions.md`.
  No per-task files.
- Rationale: Small project; a single status table and one decision log are easier to keep true.

## D7 — Repository language

- Decision: All repository content in English (code, comments, commit messages, docs, README).
  Conversations with the owner in Turkish.
- Rationale: Portfolio-readable repository; owner prefers Turkish discussion.

## D8 — Video scope

- Decision: Video is out of scope for now. Demo evidence = single-page web demo, Java sample-event
  client, deterministic dedupe script, and real transcripts/screenshots in README.

## D9 — Stack baseline and environment

- Decision: Java 21 LTS as build target (`maven.compiler.release=21`); Spring Boot 4.1.x latest
  patch; Maven wrapper (Maven 3.9.11 installed); PostgreSQL pinned via Docker image tag (exact tag
  confirmed in T1.2; candidate `postgres:18-alpine`); Testcontainers for integration tests.
- Context/evidence: `java -version` → Temurin 21.0.8 LTS; `mvn -version` → Java 25 (Homebrew);
  Docker 29.8.1 + Compose v5.5.1 running; `JAVA_HOME` unset.
- Rationale: Target the deterministic default JDK and stay independent of an unset `JAVA_HOME`;
  `release=21` builds identically under both JDKs.
- Status: finalized with T1.1/T1.2 evidence (2026-10-18):
  - Spring Boot **4.1.1** (`spring-boot-starter-parent`, latest 4.1.x GA patch on Maven Central).
  - Maven wrapper 3.3.4 pinning Apache Maven **3.9.11**; `./mvnw -version` resolves Temurin
    21.0.8 from PATH when `JAVA_HOME` is unset; `maven.compiler.release=21`.
  - Flyway **12.4.0** via the new `spring-boot-starter-flyway` plus `flyway-database-postgresql`
    (both version-managed by Boot).
  - Testcontainers **2.0.5** (`testcontainers-postgresql`, `testcontainers-junit-jupiter`,
    version-managed by Boot).
  - springdoc-openapi **3.1.1** (`springdoc-openapi-starter-webmvc-ui`; the v3 line is the one
    that supports Spring Boot 4, per springdoc.org).
  - PostgreSQL image **`postgres:18-alpine`** (PostgreSQL 18.6), volume mounted at
    `/var/lib/postgresql` (mount point changed in the v18 image; see docker-compose.yml).
  - Hibernate `ddl-auto: validate` chosen: a no-op with zero entities, verified against the
    Testcontainers database; Flyway owns the schema from Stage 2 on.
  - Spring Boot 4 renames applied vs the 3.x names: `spring-boot-starter-web` →
    `spring-boot-starter-webmvc`, dedicated `spring-boot-starter-flyway`, Testcontainers 2.x
    artifact names (`testcontainers-postgresql`, `testcontainers-junit-jupiter`).
  - Jackson 3 note (Stage 2 evidence, corrected in review round 1): Boot 4 wires **Jackson 3**
    (`tools.jackson.*`, 3.1.5) for Spring MVC. Jackson 2
    (`com.fasterxml.jackson.core:jackson-databind` 2.21.5) is still a **compile/runtime** dependency
    via `springdoc-openapi-starter-common`, and `jackson-annotations:2.21` is a required dependency
    of Jackson 3's databind. Two Jackson generations therefore coexist at runtime (Spring MVC uses
    Jackson 3, springdoc uses Jackson 2); application code must import `tools.jackson`.

## D10 — Canonical content hash (T2.2)

- Decision: SHA-256 (lowercase hex, 64 chars) over a single canonical JSON object containing exactly
  `eventId`, `userId`, `eventType`, `source`, `occurredAt` (normalized to the UTC instant string,
  truncated to microsecond precision) and
  `metadata` (canonicalized). Keys sorted at every level (`ORDER_MAP_ENTRIES_BY_KEYS`). Absent
  metadata ≡ explicit JSON null → JSON `null`; any present value (even `{}`) is different content.
  Numbers canonicalized as exact decimals (`stripTrailingZeros().toPlainString()`), so `1`, `1.0`
  and `1.00` hash identically. `occurredAt` must be strict ISO-8601 with offset; malformed input
  fails fast (Bean Validation surfaces it as 400 in Stage 3). Null content fields fail fast. The
  utility (`io.auditbox.events.EventContentHasher`) is stateless/static and exposes `canonicalJson()`
  for inspectability.
- Known limit: a metadata number that reached the tree as a JSON double canonicalizes through its
  shortest round-trip decimal (`Double.toString`). Stage 3 must read metadata with
  `USE_BIG_DECIMAL_FOR_FLOATS` to keep client decimals exact end to end.
- Review round 1 (2026-10-18): `occurredAt` is truncated to microseconds before hashing —
  PostgreSQL `timestamptz` stores microseconds, so hashing sub-microsecond nanoseconds would
  false-409 a retry that echoes the API-returned `occurredAt`. The number-canonicalization unit
  tests now run against both the default (DoubleNode) mapper and the `USE_BIG_DECIMAL_FOR_FLOATS`
  (DecimalNode) mapper; exact high-precision behavior is proven only on the latter, matching the
  known limit above.
- Rationale: deterministic across formatting differences, so equivalent retries do not false-conflict.
  Rejected: hashing raw request bytes (formatting changes would break idempotency).

## D11 — Events schema v1 (T2.1)

- Decision: one `events` table: identity `id` PK; named unique constraint `uq_events_event_id` on
  `event_id` (D4 idempotency guard); keyset index `(occurred_at DESC, event_id DESC)` (D3);
  single-column B-tree indexes on `user_id`, `event_type`, `source` for now; `timestamptz`
  everywhere; `metadata jsonb` nullable; `received_at`/`created_at` default `now()`; `content_hash`
  text not null.
- Rationale: plain and indexable; composite filter indexes deferred until Stage 4 EXPLAINs show a
  need (noted in the migration comment).
- Evidence: schema test asserts columns/types/nullability/defaults/constraints/indexes on
  Testcontainers `postgres:18-alpine`; the compose database shows `flyway_schema_history` at v1.
- Review round 1 (2026-10-18): both timestamp columns are kept with distinct intent — `received_at`
  is the service receipt time returned by the API (DB clock at insert; a future importer may
  override it to preserve an original receipt time), while `created_at` is row-insert bookkeeping.
  They coincide today.
