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
- Stage 3 implementation note (2026-10-18): the MVC mapper reads JSON floats as `BigDecimal` via
  `spring.jackson.deserialization.use-big-decimal-for-floats: true`; Hibernate's `jsonb` read path
  uses an application-provided Jackson 3 `FormatMapper` with the same feature
  (`JsonFormatMapperConfiguration`), because Hibernate's default Jackson 3 mapper reads JSON
  numbers as double nodes. End-to-end precision is proven by `EventApiControllerTest`
  (`1.00000000000000000001` stored and returned exactly; posting `1` for the same eventId → 409).
- Review round 2 (2026-10-18, completed in re-review 2b/2c): metadata numbers are bounded at
  validation by `@ValidMetadata` on two forms, both computed in `long` from
  `BigDecimal.precision()/scale()` in O(1) so huge-exponent literals are rejected before any
  allocation: the canonical (trailing-zeros-stripped) form at 1000 integer + 1000 fractional
  digits, and the stored (unstripped, literal-scale) form at 4000 total plain digits. Integer
  digits are checked on the unstripped instance before `stripTrailingZeros()`, so values like
  `100e2147483647` cannot overflow the strip and surface as a 500 (re-review 2c F1). The read
  side is aligned at 4096 digits (`max-number-length` for MVC and for Hibernate's `FormatMapper`;
  the limit counts digits), above the 4001 digits (4003 characters including sign, decimal point
  and leading zero) the stored-form bound allows, so every accepted number survives the jsonb
  read-back and Hibernate's save-time deep copy; `long` arithmetic prevents integer wrap for
  exponents such as `1e2147483647`. The validator reuses the hasher's `canonicalNumber()`, so
  validation and hashing cannot drift. BigDecimal values are rendered as plain decimals (no
  exponent notation) on both mappers; JSON object key order is not significant and may be
  normalized by jsonb (review round 2 F4).
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

## D12 — Write API contract and idempotency mechanics (T3.1/T3.2)

- Decision: `POST /api/v1/events` → 201 (new), 200 (identical retry with the stored record), 409
  (same eventId, different content; RFC 9457 Problem Details with an `existingEvent` extension in
  the success shape). Success bodies expose `id, eventId, userId, eventType, source, occurredAt,
  metadata, receivedAt`; the internal `content_hash` is never exposed. `occurredAt` is normalized
  to UTC and truncated to microseconds before storage, so the 201 response and later re-reads
  render identically.
- Errors: 400 Problem Details with a sorted `errors` list (field, message) for Bean Validation and
  unreadable bodies (malformed JSON, oversized number literals → 400, never 500); 400 also for
  request content PostgreSQL rejects as a data exception (SQLState class 22: NUL characters,
  numeric overflow) with a generic detail; 409 as above; unexpected errors → generic 500 (logged,
  no internals in the body). Framework errors raised while handling `POST /api/v1/events` keep the
  same Problem Details shape through `ResponseEntityExceptionHandler`; a 405 is raised during
  handler mapping, outside the controller-scoped advice.
- Validation: `eventId`/`userId`/`eventType`/`source` not blank, max 128; `occurredAt` ISO-8601
  offset date-time as accepted by `OffsetDateTime.parse` (an offset is required; fractional
  seconds optional) and within `0001-01-01T00:00:00Z..9999-12-31T23:59:59.999999Z` — the same
  range the read side enforces (D13), so a stored event is always a cursor position the read
  path accepts; `metadata` any JSON value, bounded per number to 1000 integer and 1000
  fractional digits in canonical decimal form and 4000 total digits in the stored (unstripped)
  form (`@ValidMetadata`); absent ≡ null.
- Rendering: BigDecimal numbers are written as plain decimals (no exponent notation) on both the
  MVC and Hibernate mappers, so `1e2` renders as `100`; JSON object key order is not significant
  and jsonb may normalize it on read-back.
- Mechanics: hash before insert; insert in a dedicated `REQUIRES_NEW` transaction
  (`EventInsertService` via `TransactionTemplate`) so a unique violation cannot poison an outer
  transaction; on SQLState 23505 (found in the cause chain — Spring ORM 7 translates it to a
  generic `DataIntegrityViolationException`, not `DuplicateKeyException`) re-read the committed
  winner and compare hashes.
- Interim auth: a temporary basic-auth chain is active only in the `test` and `local` profiles;
  a profile-less (`prod`-like) run keeps Boot's default until Stage 5 adds the X-API-Key chain.
- Rationale: first-writer-wins with explicit conflict signalling, no silent overwrite; the
  separate insert transaction is the standard fix for the PostgreSQL abort/rollback-only pitfall.

## D13 — List API contract and keyset pagination (T4.1/T4.2)

- Decision: `GET /api/v1/events` with optional exact-match filters `userId`, `eventType`,
  `source`; optional inclusive bounds `occurredFrom`/`occurredTo` (ISO-8601 with offset); an
  optional opaque `cursor`; `size` default 20, 1..100. Blank filters count as absent; repeated
  filter parameters are comma-joined by Spring MVC's request-parameter binding (documented, not
  rejected), while
  repeated `size`/`cursor` values fail their own validation with 400. Every occurrence
  date-time — write-side `occurredAt`, read-side bounds and cursor positions — must be inside
  `0001-01-01T00:00:00Z..9999-12-31T23:59:59.999999Z`; the write side enforces the same range,
  so cursor encoding and decoding are symmetric for rows written by this API version (review
  round 3b, F-1). Order `occurred_at DESC, event_id DESC` (D3). Response envelope
  `{"items":[EventResponse...],"nextCursor":...}`; `nextCursor` is non-null only when a
  (size + 1)-th row exists; no total count.
- Cursor: URL-safe Base64 without padding over a strict JSON document
  `{"occurredAt":"<ISO-8601 UTC instant>","eventId":"<id>"}`; the instant uses
  `OffsetDateTime#toString` precision (seconds omitted only when the seconds and the fraction
  are both zero; fraction 0, 3 or 6 digits). Decode requires exactly these two textual fields
  (duplicate keys rejected), a parseable date-time inside the supported range, and a non-blank,
  NUL-free 1..128-character eventId; anything else is 400. The encoded token is capped at 1200
  characters — the worst cursor for values this write contract accepts is 1100, and a legacy
  six-digit-year position reaches 1104 — so the API never emits a cursor it would reject for
  rows written by this version. Rows inserted out-of-band with `occurredAt` outside the range
  are outside that guarantee and would need migration/removal. The cursor is opaque and unsigned
  (clients may craft positions; it is not an authorization token), but crafting cannot produce a
  500 or an unbounded scan.
- Query: Spring Data JPA 4.1 fluent Specification API
  (`findBy(spec, q -> q.sortBy(...).limit(size + 1).all())`) — exactly one SELECT per page;
  `Page<T>` was rejected because it issues a COUNT for the total (asserted via Hibernate
  statistics in the tests).
- Errors: malformed cursor, non-ISO-8601 bounds or cursor times outside the supported range
  `0001-01-01T00:00:00Z..9999-12-31T23:59:59.999999Z`, inverted range, NUL characters in
  filters/cursor, size outside 1..100 → 400 Problem Details; the detail names the offending
  parameter (no `errors` extension; that is reserved for body validation).
- Stability: the keyset predicate `(occurred_at < :cursorTime) OR (occurred_at = :cursorTime
  AND event_id < :cursorId)` makes a page walk immune to inserts between pages; a new event can
  only appear before the cursor or at the end. `event_id` ordering uses the database collation
  consistently in ORDER BY and in the predicate.
- Rationale: keyset pagination yields stable pages under concurrent writes without counting;
  the JSON+Base64 cursor keeps eventIds with special characters safe and the decode strict.
- Evidence: 34 list API tests, 19 cursor unit tests and 42 write-API tests (164 total); a live
  walk with a control-character eventId produced a 556-character cursor that replayed
  successfully (the pre-fix 512 cap rejected it); the worst cursor for values this write contract
  accepts is 1100 characters (1104 with a legacy six-digit-year position); a live 3-page walk
  traversed a 3-row equal-timestamp tie group without duplicates; write-side range enforcement
  rejects `+10000-...` with an occurredAt field error while the min/max boundaries store and
  replay.

## D14 — Static X-API-Key authentication (T5.1)

- Decision: one stateless Spring Security chain; the only authentication mechanism is the
  `X-API-Key` header validated by `ApiKeyAuthenticationFilter`. The key is
  `auditbox.security.api-key`, bound from `API_KEY`: `local`/`dev` default to `local-dev-key`,
  `test` to `test-api-key`, and `prod` fails fast at startup when the key is missing or blank
  (`ApiKeyProdRequirement`); a blank configured key matches nothing (deny-all).
- Constant-time comparison: SHA-256 both values, `MessageDigest.isEqual`; null/blank never
  matches; neither the key nor the header value is logged or stored in the security context
  (the principal is a static client name).
- Responses: missing/wrong key → 401 RFC 9457 Problem Details (`application/problem+json`,
  title Unauthorized, detail naming `X-API-Key`) plus `WWW-Authenticate: X-API-Key`.
  `/actuator/health/**` and the static demo page (`/demo/**`, T6.2/D15) are public; everything else
  requires the key (events, other actuator
  endpoints, docs when enabled). `DispatcherType.ERROR` dispatches are permitted so an
  authenticated 404/405 is not masked as 401 by the error page; the original request still
  passes the key check.
- Swagger/OpenAPI: disabled by default; enabled in the `dev` profile for humans (run
  `local,dev`) and in `test` for the OpenAPI tests; docs stay key-protected. The OpenAPI
  document declares an `ApiKeyAuth` apiKey scheme (header `X-API-Key`) with a global security
  requirement.
- Cleanup: the Stage 3 temporary basic-auth chain and `spring.security.user.*` are deleted;
  Boot's unused default in-memory user (`UserDetailsServiceAutoConfiguration`) is excluded so no
  generated password is logged and no unused authentication path exists.
- Rationale: simplest credible service-to-service auth; no sessions/CSRF; fail-fast prevents a
  blank-key production; constant-time comparison avoids timing leaks; docs stay off unless
  intentionally enabled.
- Evidence: 187 tests at sign-off after the round-4 fixes (12 security, 6 matcher,
  4 prod-requirement); the initial Stage 5 implementation had 182 (10/5/3). Live: 401 without/wrong key
  (Problem Details + `WWW-Authenticate`), 200 GET / 201 POST with the key, health public, other
  actuator 401, docs 401 without a key, unknown path 404 and PUT 405 with a key (not 401).
- Review round 4 (2026-10-18, all findings closed): raw invalid percent-escapes in the query
  string now return 400 (Tomcat `InvalidParameterException` handler, F1); logout is disabled —
  without it the default `LogoutFilter` answered `/logout` with a 302 before authorization (F2);
  the configured key is stripped before comparison and a whitespace-only key fails startup in
  prod (F3); the README notes the Swagger UI needs a header-capable client (F4). Tests pin
  `WWW-Authenticate`, no echo of the submitted key, header case-insensitivity, exactly-one-header
  note, and that a wrong-key write stores nothing (187 tests total).

## D15 — Demo package (T6.1–T6.3)

- Decision: three demo paths, all reproducible from the README:
  - `io.auditbox.demo.SampleEventClient` — JDK-only client (`java -cp target/classes
    io.auditbox.demo.SampleEventClient`), flags `--base-url`/`--api-key`/`--count`/`--batch`;
    eventIds are `demo-<batch>-<i>` and payloads depend only on `(batch, i)`, so re-running a
    batch replays identical content (first run 201s, replay 200s).
  - Static single-page browser demo at `/demo/index.html` — vanilla HTML+JS, no build step; the
    visitor pastes the `X-API-Key` into the page and every API call still goes through the same
    filter chain. The page itself is public (`/demo/**` permitAll) and contains no secrets; the
    events API stays key-protected.
  - `scripts/dedupe-demo.sh` — deterministic four-scenario dedupe evidence (N parallel identical
    POSTs → 1×201 + (N−1)×200 and one row; identical retry → 200; different content → 409 with
    `existingEvent`; fresh read shows the single row). Exits non-zero on any failed expectation;
    configurable via `BASE_URL`, `API_KEY`, `DB_PORT`, `CONCURRENCY`.
- Rationale: the demo must be reproducible with zero extra infrastructure and must not soften
  the security model (the API key is still required for every API call).
- Evidence: 201 tests (3 static-page tests + client unit tests); live client run 5×201 then
  5×200; script 4/4 PASS; real screenshot `docs/images/demo.png` (headless Chrome, local profile)
  and real transcripts in the README.
- Review round 5 (2026-10-18, all findings closed): the dedupe script's run id is per-process
  (`<epoch>-<pid>`), so back-to-back runs cannot collide (F1); the client validates `--base-url`,
  normalizes trailing slashes, prints non-null failure text, and exits 1 on connection failures
  or any status ≥ 400 (F2–F5); the script preflights the DB capability before any write and no
  longer prints the key length (F6–F8); the page snapshots the filter query for cursor walks,
  resets the cursor on filter edits and uses a random fallback eventId (F10–F11); README notes
  updated (F9, F12). Evidence: 207 tests; four back-to-back script runs all exit 0 with
  1×201 + 9×200 and one row; client error paths (exit 2/1/1) verified live.
- Round 5b sign-off (2026-10-18): verdict **"sound"**; two cosmetic script notes (raw curl/psql
  stderr on preflight failures) were fixed in the sign-off commit, so failures print only the
  prepared messages. **Stage 6 signed off.**
- Review round 6 (2026-10-18, all findings closed): the "health-only public" wording was
  qualified — the static demo page under `/demo/**` is the only other public path (it carries no
  secrets and every API call it makes still passes the key check; `DemoStaticPageTest`); the
  malformed-JSON error shape is now described as a generic detail, with a new integration test;
  the duplicate-header behavior gained a test (first value wins); the README's OTAP sentence and
  the evidence wording were corrected (F1-F9).

## D16 — Post-handoff M9 enhancements (CI, container, observability, SBOM)

- Decision: extend the delivered service with portfolio/ops improvements without changing the
  existing API semantics:
  - **CI:** GitHub Actions on every push/PR to `main` — build + `./mvnw -B verify` against
    Testcontainers, a second job that builds the Docker image, an SBOM upload step that runs on
    every CI run (it uploads whenever the build reached `package`; a failure before that has no
    file to upload and the step never fails the job), and surefire reports on failure; a README
    badge links to it.
  - **Container:** multi-stage Dockerfile (Maven build → Temurin 21 JRE, non-root), a
    `.dockerignore`, and an opt-in compose `app` profile (`docker compose --profile app up
    --build`) that waits for the db healthcheck. The default db-only workflow is unchanged.
  - **Single fetch:** `GET /api/v1/events/{eventId}` returns the stored record or 404 Problem
    Details (generic detail), behind the same API key.
  - **Metrics:** Micrometer counters `auditbox.events.created/replayed/conflicts` on the write
    decision points and `auditbox.auth.failures` tagged `reason=missing|invalid` (closed tag
    set; the key is never a tag, value, or log line). `/actuator/metrics` is exposed and
    key-protected; health stays public.
  - **Correlation:** `X-Request-Id` is echoed when it matches `[A-Za-z0-9._-]{1,128}` and
    generated otherwise; it is stored in the `requestId` MDC key, echoed on every response
    (including 401s) and cleared per request. `prod` logs ECS JSON via Spring Boot's built-in
    structured logging; local/dev/test keep plain logs.
  - **SBOM:** CycloneDX Maven plugin 2.9.3 writes `target/bom.json` (CycloneDX 1.6) during
    package; CI uploads it as the `sbom` artifact.
- Rationale: close the final review's operational gaps at EUR 0 (public-repo Actions, OSS
  tooling) while keeping the single-service/single-table scope.
- Evidence: 226 tests green before the review; CI run green (3275cbc: build 1m36s, image
  1m15s, `sbom` artifact 58,574 B); live probes: 201 with the echoed `X-Request-Id`, by-id
  200/404, metrics 401 without / 200 with the key, auth-failure `reason=missing` tag, ECS prod
  logs, `bom.json` with 112 components.
- Review round (2026-10-19, read-only, M9.1–M9.6): verdict **"sound with issues"**; all
  findings closed — F1 the compose `app` service really carries `profiles: ["app"]` now
  (`docker compose config` verified), F2 auth-failure counting moved from the raw-path filter
  check to the authentication entry point, which runs only when a required key was rejected
  (percent-encoded public paths no longer counted; regression test added — 227 tests), F3 the
  project's `makeBom` execution sets `skipAttach` so the parent's default execution attaches
  the single SBOM artifact (duplicate-attach warning gone), F4–F7 wording/examples/timings
  corrected (404 example matches the live body, "all endpoints", D1–D17). F8 notes (no
  MDC-cleanup test, exact-equality assertion under hypothetical JUnit parallelism, floating
  base-image tags) accepted as-is.
- Open owner decisions: LICENSE type and a Playwright (Java) end-to-end test for the demo page
  (browser binary download) were both decided on 2026-10-19 — see D17; the Playwright suite
  itself is tracked as M9.7.

## D17 — MIT license and a Playwright E2E suite for the demo page

- Decision: license AuditBox under **MIT** (owner choice on 2026-10-19; `LICENSE`, `pom.xml`
  `<licenses>`, README note), and approve a **Playwright for Java** end-to-end suite for the
  static demo page as an opt-in Maven profile (`-Pe2e`) plus a manually triggered CI job, so
  the default `./mvnw verify` runtime and the push pipeline are unchanged.
- Rationale: MIT is the simplest well-understood option for a portfolio repository and removes
  the "all rights reserved" default; a real-browser test is the only honest way to claim the
  demo page works, while the separate profile keeps the main suite fast.
- Consequences: the E2E run downloads a Chromium build (~300 MB, free) on first use; it is not
  part of the default `verify` and its CI wiring must not slow the push pipeline. The suite is
  delivered as M9.7 and its evidence recorded in the README/plan.
- Delivered (M9.7, 2026-10-19): profile `e2e` in `pom.xml` (Playwright 1.63.0 test-scoped,
  `src/e2e/java` added via build-helper-maven-plugin, Surefire limited to `**/*E2ETest.java`);
  `DemoPageE2ETest` (5 tests, Testcontainers
  PostgreSQL, real Chromium, trace-on-failure under `target/playwright-traces`); manually
  triggered `.github/workflows/e2e.yml`. Default `./mvnw -B verify` re-verified: 227 tests,
  BUILD SUCCESS; `./mvnw -B -Pe2e verify`: 5 tests, BUILD SUCCESS. The manual workflow has
  run green on GitHub for `624edfe` (102 s, `surefire-reports` artefact; no traces because no
  test failed).
- Review round 2 (2026-10-19, read-only, M9.7/M9.8 delta): verdict **"sound with issues"**;
  all findings closed — F1 the seed stamps are computed from one truncated base instant, so
  adjacent events can no longer share a second and the exact-order assertion is
  deterministic; F2 the manual workflow caches `~/.cache/ms-playwright` (actions/cache@v6),
  which makes the "cached after the first run" claim true on CI (verified: the second manual
  run restored the cache and finished in 71 s vs 102 s); F3 CI installs Chromium with
  `--with-deps` (driven by `PLAYWRIGHT_INSTALL_WITH_DEPS`; verified that this is the only
  configuration in which Playwright's apt dependency step runs). The host-validation warning
  about full-browser libraries remains cosmetic: the suite runs headless and is green with
  and without the flag; F4 the pom comment now states that the Boot parent pins build-helper
  3.6.1 and this profile uses 3.6.2 explicitly; F5 the README "Next steps" lists Playwright
  as done; F6–F8 the trace wrapper no longer masks test failures, the Chromium install checks
  its exit code and runs through the test JVM's own `java`, and the manual workflow has a
  30-minute timeout.

## D18 — Per-source hashed API-key registry with rotation (M10.1)

- Context: D5 shipped a single static plaintext API key. An owner-approved hardening step
  replaces it: the server now holds only SHA-256 digests, one or more per source, so a
  configuration read (backup, `.env` copy, incident dump) no longer exposes working
  credentials and different callers can be told apart from the configuration alone.
- Decision: `auditbox.security.api-keys`, bound from the `API_KEYS` environment variable,
  holds comma-separated `source:sha256hex` entries parsed once at startup by
  `ApiKeyRegistry`. `source` is 1..64 characters of `[A-Za-z0-9._-]` and becomes the
  authenticated principal (authority stays `ROLE_API_CLIENT`); `sha256hex` is exactly 64 hex
  characters (upper case accepted, normalized to lower case). Entries and their parts are
  trimmed of surrounding whitespace; empty entries (stray commas) are ignored. The plaintext
  keys are never stored, logged, or echoed — the configuration already contains only
  digests, generated with `printf %s 'the-key' | sha256sum`.
- Rotation by overlap: the same source may be listed more than once — each entry is an
  independently valid key. Procedure: add `source:<new-digest>` next to the existing entry,
  distribute the new key to the client, then remove the old entry (config reload means a
  restart). Removal ends the overlap window; there is no timetable, the window is purely
  configuration-driven.
- Comparison: constant time and position-agnostic — the provided value is SHA-256-hashed
  once, then compared with `MessageDigest.isEqual` against every stored digest; the loop
  never exits early so timing does not reveal the matched position. The first matching
  entry's source wins (two entries sharing a digest authenticate as the first-listed
  source). Null/blank provided values never match.
- Deny-all: a blank/absent value yields an empty registry — nothing authenticates (the same
  behavior a blank D5 key had).
- Fail-fast: a malformed entry (missing colon, bad source, wrong-length or non-hex digest)
  aborts startup in every profile with an `IllegalStateException` naming the entry index
  and the reason — never echoing the digest or any other key material. In `prod`,
  `ApiKeyProdRequirement` additionally fails when the property is blank or parses to zero
  entries; its message names `auditbox.security.api-keys` and the `API_KEYS` environment
  variable.
- Profiles: `local`/`dev` default to the digest of the documented `local-dev-key`, `test` to
  the digest of `test-api-key` — the documented local development key and every client that
  sends a key (demo page, `SampleEventClient --api-key`, `scripts/dedupe-demo.sh API_KEY`)
  keep their names and behavior unchanged. `ApiKeyMatcher` remains as the digest helper.
- Rationale: hashed-only configuration limits the blast radius of a config leak, per-source
  principals attribute server-side traffic, and overlap rotation avoids an all-clients
  key swap — while staying a single property with no new tables, services, or endpoints.
  Nothing else changes: entry point 401/Problem Details, the auth-failure metric
  (`reason=missing|invalid`, never tagged with key material), `X-Request-Id`, and the public
  surface stay as they are.
- Evidence: `ApiKeyRegistryTest` (parsing, rotation overlap, normalization, deny-all,
  malformed fail-fast with no digest echo, first-match resolution), `ApiKeyMatcherTest`
  (digest helper pinned to the documented constants), `ApiKeyProdRequirementTest`
  (blank/whitespace/zero-entries/malformed abort, valid entry starts), and the unchanged
  `ApiKeySecurityTest` real-HTTP 401/401/201 pattern.

## D19 — In-memory per-source rate limiting (M10.2)

- Context: M10 hardening continues (D18 keys, rate limiting, dependency automation). The
  owner-approved scope is deliberately small: a single-instance, in-memory limiter with no
  Redis, no new service, no new table, and no new dependency — a hand-rolled token bucket.
- Decision: every authenticated request (key-accepted, D18 source) consumes one token from
  a per-SOURCE token bucket; the SOURCE is the authenticated principal name, so all of a
  source's registered keys share one bucket and are limited together. Bucket capacity =
  `auditbox.rate-limit.requests-per-minute` and refill is continuous at
  `requests-per-minute / 60` tokens per second, so a source can burst up to the limit
  instantly and then sustains exactly limit/60 per request-second after that. Buckets live
  in a `ConcurrentHashMap`; the key set is bounded by the configured sources and cannot
  grow with attacker-chosen input.
- Rejection: HTTP 429 as RFC 9457 Problem Details (`application/problem+json`, title
  "Too Many Requests", a generic detail naming no key material or source) plus a
  `Retry-After` header carrying the whole seconds (ceil, minimum 1) until at least one
  token is available.
- Placement: `RateLimitFilter` joins the existing security chain AFTER the authorization
  filter, so it runs only for admitted requests and relies on the SecurityContext
  principal — unauthenticated requests keep their 401 (never 429), and the public paths
  (`/actuator/health/**`, `static /demo/**`) are never limited when requested without a
  key. Requests that carry a valid key are authenticated requests and — like every other
  authenticated request — consume a token, also when they hit an otherwise public path.
  Non-events authenticated endpoints (e.g. `/actuator/metrics`) are limited like the rest
  of the authenticated surface. `ERROR` dispatches are not filtered.
- Configuration: `auditbox.rate-limit.enabled` (`RATE_LIMIT_ENABLED`, default `true`)
  and `auditbox.rate-limit.requests-per-minute` (`RATE_LIMIT_REQUESTS_PER_MINUTE`,
  default 300). When enabled the value must be ≥ 1: a lower value aborts startup with an
  `IllegalStateException` naming the property — never a silent clamp. With the limiter
  disabled any value binds but nothing is enforced. The `test` profile sets
  `requests-per-minute: 1000000` so the existing integration tests cannot trip the
  limiter; the dedicated rate-limit tests override it via test properties.
- Observability: one new counter `auditbox.rate.limited`, incremented exactly once per
  emitted 429 — without tags, so caller-side source names cannot drive the counter
  cardinality. Existing counters and the auth-failure `reason` tags are unchanged.
- Testing seam: `RateLimiter` takes a `LongSupplier` nanotime (a package-private test
  constructor), so refill behavior is tested with a fake clock and no sleeps: exact
  burst/Retry-After arithmetic, exact one-token restoration at the interval boundary
  (`60e9 / requestsPerMinute` ns), per-source isolation, and rejection side effects.
- Single-instance caveat, explicit: the limit is per process. A multi-instance deployment
  (replicas behind a load balancer) would multiply the effective limit per source and
  would need a shared store (e.g. Redis) — deliberately out of scope; revisit only with
  real need.
- Rationale: bounded, dependency-free protection against a single runaway credentials
  holder; Problem Details + Retry-After give clients a machine-usable signal without
  inventing new response shapes. In-memory (rather than user-visible tables) keeps the
  events-service scope unchanged.
- Evidence: `RateLimiterTest` (6 unit tests), `RateLimitPropertiesTest` (5 context-runner
  tests), `RateLimitApiIntegrationTest` (real HTTP: 200/200/429+`Retry-After`,
  per-source isolation, key-less 401, public health 200, counter delta) — 254 tests
  green; live local run documented in the README "Rate limiting" section.

## D20 — Dependency update automation with Dependabot (M10.3)

- Decision: `.github/dependabot.yml` — weekly version-update pull requests for Maven and
  GitHub Actions, minor/patch updates grouped into one PR per ecosystem, an open-PR limit of
  5, and conventional commit-message prefixes (`chore(deps)`, `ci(deps)`). No auto-merge and
  no change to the push pipeline: every Dependabot PR runs the same `ci.yml` build+verify,
  image, and SBOM jobs as a human PR. GitHub's Dependabot security updates stay enabled
  independently of this schedule.
- Rationale: the project pins Spring Boot 4.1.x, the CycloneDX and build-helper plugins, the
  Maven wrapper, and the GitHub Actions majors; a zero-install, free bot keeps them current
  and surfaces advisories, without adding a runtime dependency, service, or CI cost.
- Consequences: at most ~5 open PRs per ecosystem; major bumps arrive individually for
  review; updates are never merged automatically.
- Evidence: the configuration file itself (parsed by GitHub once it lands on the default
  branch); the first scheduled PRs appear on the configured weekly cadence.

## D21 — Monthly RANGE partitioning and the per-eventId advisory lock (M11.1)

- Context: after handover, retention/rotation requires the single `events` table to be
  organized by time (M11.1 of the post-handoff milestone list). The owner approved monthly
  RANGE partitions on `occurred_at`; retention and automatic partition creation are the NEXT
  task (M11.2) — this decision records only the schema conversion and the write-path change
  it forces.
- Decision:
  - `events` becomes `PARTITION BY RANGE (occurred_at)`. Monthly partitions are named
    `events_YYYY_MM` with half-open bounds `[month start UTC, next month start UTC)`; the
    app already normalizes `occurredAt` to UTC (D10), so the bounds are stable instants. A
    `DEFAULT` partition (`events_default`) catches every `occurredAt` inside the documented
    API range (`0001-01-01..9999-12-31`, D13) that no monthly partition covers, so all
    API-legal events stay storable before M11.2 arrives. The V2 migration creates the
    initial window relative to its run: current month − 25 through current month + 3.
  - The identity column stays (`GENERATED BY DEFAULT AS IDENTITY`; PostgreSQL 18 supports
    identity on partitioned tables). The partitioned primary key must include the partition
    key, so the PK is `(occurred_at, id)` instead of `(id)`.
  - **Uniqueness trade-off:** a partitioned unique index must include the partition key, so
    the V1 table constraint `uq_events_event_id` on `(event_id)` is replaced by a unique
    INDEX on `(occurred_at, event_id)` (name kept). The database therefore keeps only
    `(occurredAt, eventId)` uniqueness; same eventId in different months would pass the DB.
  - **D4 is restored in the application:** `EventService.record` now runs in one
    `@Transactional` scope whose FIRST database statement is
    `SELECT pg_advisory_xact_lock(hashtextextended(:eventId, 0))`. Under the lock it
    pre-checks the stored row (equal content hash → idempotent replay 200, different →
    conflict 409 with the stored record, unchanged semantics and metric increments); if
    absent, it inserts through the unchanged `REQUIRES_NEW` `EventInsertService` (the inner
    transaction still commits independently and an inner failure does not mark the outer
    rollback-only). The lock is transaction-scoped and released at outer commit, after the
    inner insert committed — so a concurrent request for the same eventId blocks briefly on
    the lock and then sees the committed row in its pre-check: one eventId can never produce
    two rows, even across different months. Hash collisions of `hashtextextended` merely
    serialize two unrelated eventIds for their write duration — accepted.
  - The `23505` catch stays as a safety net for writes that bypassed the lock (e.g.
    out-of-band inserts); it can now also fire on the `(occurred_at, event_id)` index.
  - Index set on the partitioned parent: `uq_events_event_id (occurred_at, event_id)` (kept
    name), `ix_events_occurred_at_event_id (occurred_at DESC, event_id DESC)` (D3),
    `ix_events_user_id`, `ix_events_event_type`, `ix_events_source`, and — NEW — a
    non-unique `ix_events_event_id` so `GET /api/v1/events/{eventId}` and the idempotency
    pre-check stay index-assisted.
  - **Known per-eventId-lookup trade-off:** `findByEventId` no longer benefits from a single
    global unique index; it probes every child partition's `ix_events_event_id` index (29+
    index probes today, growing with the managed window). Per-eventId lookups stay fast per
    partition but lose the O(1) global guarantee. M11.2 retention bounds the partition count.
  - The copy preserves `id`, `received_at` and `created_at` verbatim; the identity sequence
    is advanced past the copied maximum (`setval`). The final table keeps the name `events`
    (single-logical-table boundary unchanged; API contract and response shapes unchanged).
- Pool headroom: a record request now holds one connection (outer transaction, advisory
  lock) while the insert runs on a second `REQUIRES_NEW` connection; the base pool is raised
  to 32 (`spring.datasource.hikari.maximum-pool-size`) so the 20-parallel race cannot
  deadlock the pool.
- Rejected: unique constraint attempt without the partition key (impossible in PostgreSQL);
  session-level `pg_advisory_lock` (leaks locks on connection reuse in a pool); inserting
  via the outer transaction with catch (rollback-only pitfall, D12).
- Evidence: `EventsSchemaMigrationTest` (V1+V2 applied clean, relkind 'p', RANGE on
  `occurred_at`, PK `(occurred_at, id)`, unique index behavior + 23505, index set, initial
  window bounds, monthly/default routing) and `EventPartitionedIdempotencyTest` (write-path
  routing, GET/list across partitions, different-occurredAt idempotency sequential and
  concurrent); the full suite and a live local run are recorded in the review report.
