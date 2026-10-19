# AuditBox — Plan

Owner-approved 2026-10-18. Executed in small tasks; no stage starts before the previous stage's
tests are green.

> Post-handoff enhancements are recorded in the decision log and the README: M9 (CI,
> container, single fetch, metrics, correlation IDs, SBOM — D16; Playwright E2E behind the
> opt-in `-Pe2e` profile — D17; MIT license) and M10 hardening (per-source hashed key
> registry — D18, in-memory rate limiting — D19, Dependabot — D20). The stage tables below
> describe the original delivered scope; the current suite is 294 tests
> (`./mvnw -B verify`), plus 5 Playwright tests under `-Pe2e`.

**Verification loop per task:** brief → implementation by a delegated GLM-5.3-Flash child task →
the orchestrator reviews the diff and runs the tests personally → fix rounds if needed → status
update in this file. At every stage boundary, an independent read-only review round runs on a
different model (DeepSeek V4.1 Flash). A "done" report alone is never accepted.

## Assumptions

- Repository content is English; conversations with the owner are Turkish.
- Out of scope: video, Kafka/event platform, multiple services, tamper-proof claims.
- Versions: Java 21 LTS, Spring Boot 4.1.x, Maven wrapper, PostgreSQL (pinned image tag).
  Exact versions recorded in `decisions.md` when Task 1.1 completes.
- Git: small task-sized commits on `main`.
- Infrastructure budget: EUR 0 (local PostgreSQL + open-source tooling only).
- Model budget: stay within OpenCode Go limits; no extra cash spend.

## Stages

### Stage 0 — Documentation and decision record

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T0.1 | `AGENTS.md` | English; commands accurate; committed | done |
| T0.2 | `docs/plan.md`, `docs/decisions.md` | English; scope matches approved plan; committed | done |

### Stage 1 — Skeleton and local run

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T1.1 | Maven project (wrapper), Spring Boot 4.1.x, Java 21; dependencies: web, data-jpa, validation, security, flyway, postgresql, actuator, springdoc; `local`/`test` profiles; integration test booting the app against Testcontainers PostgreSQL and asserting `/actuator/health` UP | `./mvnw verify` green with real output; versions recorded in `decisions.md` | done |
| T1.2 | `docker-compose.yml` (pinned PostgreSQL image, healthcheck, volume), `.env.example`, README "Getting started" draft | `docker compose config` valid; README steps reproduce a working local run; real outputs pasted in the report | done |

### Stage 2 — Schema and content hash

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T2.1 | Flyway V1: `events` table (`event_id` unique, `user_id`, `event_type`, `source`, `occurred_at timestamptz`, `received_at timestamptz`, `metadata jsonb`, `content_hash`, `created_at`) + indexes: unique(`event_id`), (`occurred_at DESC, event_id DESC`), filter columns | Migration applies on a clean database (Testcontainers); schema asserted by test | done |
| T2.2 | Canonical content hash (deterministic JSON over core fields + metadata, UTC normalized) | Unit tests: key order and timezone normalization stable; different content → different hash; rule documented in `decisions.md` | done |

### Stage 3 — POST /api/v1/events (idempotency + concurrency)

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T3.1 | DTO + Bean Validation; 201/200/409 semantics; Problem Details error model with field errors; OpenAPI schema | Validation tests green (400 on bad payload with field details) | done |
| T3.2 | Insert → catch unique violation → re-read → compare hash → 200 or 409 (with stored record summary) | Integration tests on real PostgreSQL: new → 201; identical repeat → 200 and exactly one row; different content → 409 with detail; **N concurrent identical requests → exactly one row, responses only {1×201, rest 200}, no 5xx**; concurrent different content → 1×201 + 1×409 | done |

### Stage 4 — GET /api/v1/events (filters + keyset pagination)

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T4.1 | Filters `userId`, `eventType`, `source`, `occurredAt` from/to; order `occurred_at DESC, event_id DESC`; opaque base64 cursor; default 20 / max 100; `nextCursor` only when more results exist | Filter combination tests green; **pagination walk test: full dataset traversed with no duplicates or gaps, including rows sharing the same `occurred_at`**; walk stays stable while a new event is inserted | done |
| T4.2 | Malformed cursor → 400; invalid date range → 400; OpenAPI parameter docs | Negative tests green; documented in README | done |

### Stage 5 — X-API-Key security

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T5.1 | Stateless Spring Security + API key filter; constant-time comparison; missing/wrong key → 401 (Problem Details); `prod` fails fast without a key; health public; Swagger dev-profile only | Security tests green (401/401/201 pattern); README "Authentication"; OpenAPI security scheme present | done |

### Stage 6 — Demo package

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T6.1 | Small Java client (JDK HttpClient, no extra dependencies) generating N sample events | Client run against the local app produces seeded events; usage documented | done |
| T6.2 | Single-page web demo (vanilla HTML+JS served by the app): API key input, filters, table, "load more" cursor, send-event form | Verified in a browser by the orchestrator (screenshot into `docs/images/`); README steps reproduce it | done |
| T6.3 | Deterministic dedupe script: concurrent identical eventId submissions (HTTP status distribution + DB row count) and a different-content 409 case; one command, stable output | Script output embedded in README as a real transcript | done |

### Stage 7 — Test hardening and README

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T7.1 | Acceptance criteria → test/evidence matrix in this file; gaps closed | `./mvnw verify` fully green; every criterion mapped to a test or demo evidence | done |
| T7.2 | README: Overview, What this is / is not, Architecture, API examples, Decisions summary, Getting started, Demo (real outputs), Testing & evidence, Next steps | Orchestrator follows README literally on a clean checkout and succeeds; claims match evidence | done |

### Stage 8 — Independent final review and handoff

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T8.1 | Read-only full-diff and docs review round (DeepSeek V4.1 Flash) | Findings resolved or explicitly deferred with rationale | done |
| T8.2 | Final acceptance pass + cost report | Acceptance checklist with evidence; owner gives final acceptance | done |

### Stage 11 — Post-handoff hardening: partitioned storage (M11)

> Owner-approved post-handoff milestone; see `docs/decisions.md` D21. Orchestration and
> scope: M11.1 schema conversion only — retention came as M11.2 (T11.2 below) and the
> load test is M11.3 (out of scope here). API contract, response shapes and the
> single-logical-table boundary are unchanged.

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T11.1 | Flyway V2: monthly RANGE-partitioned `events` on `occurred_at` (partitions `events_YYYY_MM` `[month start UTC, next month start UTC)`, initial window current − 25 .. + 3 months, `events_default` catch-all); migrated in place with row copy, PK `(occurred_at, id)`, unique index `(occurred_at, event_id)` (kept name `uq_events_event_id`) + new non-unique `event_id` index; write path takes a transaction-scoped advisory lock per eventId (first statement) and pre-checks before inserting in the same transaction (D21; single-connection design per the M11 review fix F1) | `./mvnw verify` green with the real new test count; schema/partition/idempotency tests pass; live evidence recorded (partition routing via `tableoid`, 201/200/409 semantics unchanged, 20-parallel race still one row) | done (M11.1): 262 tests green; live V1→V2-with-data migration preserved both rows into their monthly partitions; routing to `events_2026_08`/`events_default`, same-id-different-occurredAt → 409 with one row, 20-parallel identical → one row; F1 later reduced the write path to one connection per request |
| T11.2 | Retention and automatic partition maintenance (D22): `auditbox.retention.*` — `enabled` (true), `months` (24), `lookahead-months` (3), `default-delete-batch-size` (1000), `max-batches-per-run` (10), `schedule-cron` (daily 03:30); one `PartitionMaintenanceService` pass (testable with an injected fixed `Clock`) behind a thin startup/cron wrapper with an overlap guard: creates missing `events_YYYY_MM` partitions for the window current..+lookahead with exact UTC bounds (clock-derived DDL only), drops every monthly partition whose catalog upper bound is `<=` now(UTC) − months (rows removed with the partition BY DESIGN — this IS the retention policy), deletes older `events_default` rows in bounded batches; untagged counters (`partitions.created/partitions.dropped/default.rows.deleted`; review F2 later added `default.rows.moved`); `enabled=false` = strict no-op | Config validation aborts startup naming the property (months=0 / lookahead<0 / batch sizes=0); Testcontainers tests with a fixed clock prove the startup pass, exact ensure bounds, second-pass idempotency, all three drop rules incl. the exact-`<=` boundary, the batch cap/stop and disabled no-op; metrics asserted by deltas; live evidence with `RETENTION_MONTHS=2` (drops, lookahead creation, old DEFAULT rows deleted, counters via `/actuator/metrics`, idempotent second startup) | done (M11.2): 275 tests green; live acceptance with `RETENTION_MONTHS=2` — 23 partitions dropped on startup (cutoff 2026-08-19), `events_2027_01` recreated after a manual drop, the 2024 DEFAULT-partition row deleted, counters verified, second startup idempotent (0/0/0); full suite exit clean (test-only `server.shutdown=immediate` removed the 30 s surefire fork stall); M11 review F2 later made the ensure phase move a windowed month's stranded `events_default` rows into the new partition and attach it (bounded batches, per-month failure isolation, the `auditbox.retention.default.rows.moved` counter) |
| T11.3 | JDK-only load generator `io.auditbox.demo.LoadGenerator` (same package/style as the demo client, no new dependency/tool) + thin wrapper `scripts/load-test.sh` + README "Load test" evidence (D23): flags `--base-url/--api-key/--requests/--concurrency/--replay/--timeout-seconds/--run-id` with usage-error exit code 2; exactly `--concurrency` in-flight requests (virtual-thread workers on a shared cursor); deterministic `load-<run-id>-<i>` workload, `--replay` = a second identical wave expecting idempotent 200s; report with per-wave/total completed, wall time, throughput, status distribution (200/201/409/429/5xx/other/errors) and p50/p95/p99/max (pure JDK percentile math) | Unit tests (flag parsing/validation, deterministic workload, classification, exit codes, percentile math) join the default suite; a real load run with the rate limit raised, the psql persistence proof and a machine/stack context note documented in the README as a local baseline, not an SLA | done (M11.3): 294 tests green; real runs — 5000 POSTs @ 32: all 201, 6.32 s, 791 req/s (p50 28.8 / p95 80.5 / p99 145.1 / max 799.1 ms); 2000 @ 16 with `--replay`: 201 x2000 then 200 x2000 (replay wave 1680 req/s); 7000 rows counted in Postgres (partition-routed), zero 429s server-side; pool raised to 2 x concurrency for the run (pre-F1; the current code needs no pool override) |

## Acceptance evidence matrix

Filled in T7.1 (2026-10-18): each acceptance criterion of stages 0–6 plus the project-level
criteria mapped to the exact artefact that proves it. Evidence kinds:

- **test** — automated in the JUnit 5 suite (`./mvnw verify`, Testcontainers against the pinned
  `postgres:18-alpine` image); exact test class and method named.
- **live** — a real run against the running local stack, recorded as a transcript/screenshot in
  the README or the review log; not repeatable by `./mvnw verify`.
- **doc** — documentary artefact asserted by review, not execution.

### Stage 0 — Documentation and decision record

| Criterion | Evidence | Kind |
|---|---|---|
| T0.1 `AGENTS.md` English, commands accurate, committed | `AGENTS.md` itself (commands cross-checked against this README); repo is git-tracked at `main` | doc |
| T0.2 plan/decisions English, scope matches approved plan, committed | `docs/plan.md`, `docs/decisions.md` (D1–D15); scope boundaries re-checked in the T7.1 audit (no Kafka/scope creep; no tamper-proof claims anywhere) | doc |

### Stage 1 — Skeleton and local run

| Criterion | Evidence | Kind |
|---|---|---|
| T1.1 Maven project, Boot 4.1.x, Java 21, required dependencies | `pom.xml` — `spring-boot-starter-parent` 4.1.1, `java.version` 21, all listed starters; dependency list resolves `hibernate-core 7.4.5.Final`, `spring-data-jpa 4.1.1`, Flyway/Testcontainers/springdoc per D9 | doc |
| T1.1 `local`/`test` profiles exist | `application-local.yml`, `application-test.yml` (+ `application-dev.yml`, base `application.yml`) | doc |
| T1.1 integration test boots the app against Testcontainers PostgreSQL and asserts `/actuator/health` UP | `AuditBoxApplicationTests.actuatorHealthIsUpAgainstRealPostgres` | test |
| T1.1 `./mvnw verify` green with real output | latest full run: 211 tests (209 after the T7.1 gap-close tests plus the two T7.2 sign-off tests); earlier: 207 at stage-6 sign-off | test |
| T1.1 versions recorded in `decisions.md` | `docs/decisions.md` D9 | doc |
| T1.2 `docker-compose.yml` valid (pinned image, healthcheck, volume) | `docker-compose.yml` (image `postgres:18-alpine`, healthcheck, named volume); `docker compose config` validated by the orchestrator in the stage-1 report | doc + live |
| T1.2 README steps reproduce a working local run | README "Getting started" re-walked live by the orchestrator at stage 1; re-verified literally at T7.2 acceptance | live |

### Stage 2 — Schema and content hash

| Criterion | Evidence | Kind |
|---|---|---|
| T2.1 V1 migration applies on a clean database | `EventsSchemaMigrationTest.flywayAppliesV1ToACleanDatabase` (Flyway history at version 1) | test |
| T2.1 schema asserted (columns/types/nullability/defaults/unique constraint) | `EventsSchemaMigrationTest.eventsTableHasTheExpectedColumnsAndTypes`, `eventIdHasTheUniqueConstraint` | test |
| T2.1 unique constraint actually rejects duplicates (the race signal of D4) | `EventsSchemaMigrationTest.eventIdUniqueConstraintRejectsDuplicateEventIds` (SQLState 23505) | test |
| T2.1 keyset + filter indexes exist, keyset order is `(occurred_at DESC, event_id DESC)` | `EventsSchemaMigrationTest.keysetAndFilterIndexesExist` | test |
| T2.2 hash: key order/timezone normalization stable, canonical form pinned | `EventContentHasherTest.metadataKeyOrderDoesNotChangeHash`, `canonicalFormHasSortedKeysAtEveryLevel`, `sameInstantWithDifferentOffsetsHashesIdentically` | test |
| T2.2 hash: microsecond truncation of `occurredAt` | `EventContentHasherTest.occurredAtFractionsHashAtMicrosecondPrecision`, `subMicrosecondFractionsTruncateDownToMicroseconds` (and end to end: `EventApiControllerTest.retryEchoingTheReturnedOccurredAtDoesNotFalseConflict`) | test |
| T2.2 hash: different content → different hash | `EventContentHasherTest.changingEachContentFieldOnceChangesTheHash` (+ every number/metadata equivalence test asserting both directions) | test |
| T2.2 hash: documented rules (absent ≡ null, number equality, arrays, SHA-256/UTF-8, thread safety) | `EventContentHasherTest.absentAndNullMetadataFollowTheDocumentedRule`, `equivalentNumberFormsHashIdentically`, `hashIsLowercaseHexSha256OverTheCanonicalJson` (independent SHA-256 recomputation), `hashUsesUtf8BytesForNonAsciiContent`, `concurrentHashingMatchesSingleThreadedHash` + the rest of the class | test |
| T2.2 hash rule documented | `docs/decisions.md` D10 | doc |

### Stage 3 — POST /api/v1/events

| Criterion | Evidence | Kind |
|---|---|---|
| T3.1 DTO + Bean Validation → 400 Problem Details with field errors | `EventApiControllerTest.invalidRequestsReturn400ProblemDetailsWithFieldErrors` (9 parameterized cases naming the offending field); malformed JSON generic detail: `malformedJsonReturns400ProblemDetailsWithAGenericDetail`; oversized/huge-exponent metadata numbers: `oversizedNumberInMetadataReturns400Not500` + the boundary tests below | test |
| T3.1 201/200/409 status semantics | `EventApiControllerTest.newEventIsStoredAndReturnedWith201`, `identicalRetryReturns200WithTheStoredRecordAndKeepsSingleRow`, `sameEventIdWithDifferentContentReturns409WithExistingEvent` (6 content variants, `existingEvent` extension asserted against the stored row) | test |
| T3.1 OpenAPI schema for the events path | `EventApiControllerTest.openApiDocsDescribeTheEventsPath` (summary, 200/201/400/409, `CreateEventRequest` required/bounds) | test |
| T3.2 insert → catch unique violation → re-read → compare hash → 200 or 409 | the 200/409 tests above (with hash comparison in the 409 path) plus the equivalent-form replay (`occurredAt` offset, key order, `1.00`) in `identicalRetryReturns200WithTheStoredRecordAndKeepsSingleRow` | test |
| T3.2 N concurrent identical requests → exactly one row, {1×201, rest 200}, no 5xx | `EventApiControllerTest.concurrentIdenticalRequestsProduceExactlyOne201AndExactlyOneRow` (20 requests, race via CyclicBarrier on real HTTP); reproduced end to end by `scripts/dedupe-demo.sh` (README transcript) | test + live |
| T3.2 concurrent different content → 1×201 + 1×409 | `EventApiControllerTest.concurrentDifferentContentRequestsProduceOne201AndOne409AndOneRow` | test |
| T3.2 (hardening) exact jsonb precision end to end; plain-decimal rendering; SQL data errors → 400 | `EventApiControllerTest.highPrecisionMetadataSurvivesEndToEndAsExactJsonb`, `nestedMetadataRoundTripsAndIsStoredAsRealJsonb`, `decimalMetadataNumbersRenderAsPlainDecimalsInEveryResponse`, `nullCharacterInUserIdSurfacesAs400AndStoresNoRow`, `nullCharacterInsideMetadataStringSurfacesAs400AndStoresNoRow`, plus the number-bound tests (`metadataNumberAtThe*Bound*`, `metadataNumberWith*Returns400AndStoresNoRow` — round 2/2b/2c closures) | test |

### Stage 4 — GET /api/v1/events

| Criterion | Evidence | Kind |
|---|---|---|
| T4.1 filters (alone and combined), inclusive bounds, blank ≡ absent | `EventListApiControllerTest.filterByUserIdReturnsOnlyThatUsersEventsNewestFirst`, `filterByEventTypeReturnsOnlyMatchingEvents`, `filterBySourceReturnsOnlyMatchingEvents`, `occurredAtRangeBoundariesAreInclusiveAndAcceptOffsets`, `combinedFiltersApplyConjunctively`, `noMatchingFilterReturnsAnEmptyPage`, `blankFilterValuesAreTreatedAsAbsent` | test |
| T4.1 order `occurred_at DESC, event_id DESC` | `paginationWalkTraversesAllEventsWithoutGapsOrDuplicates` (full 45-row walk sorted assertion) | test |
| T4.1 opaque base64url cursor, JSON payload, special/control characters, range boundaries | `EventCursorTest.encodingIsBase64UrlWithoutPaddingOverAJsonDocument`, `roundTripsTheKeysetPosition`, `roundTripsEventIdsWithSpecialCharacters`, `roundTripsAMaximumLengthControlCharacterEventId`, `subMicrosecondPrecisionIsTruncatedOnEncode`, `roundTripsTheSupportedRangeBoundaries` | test |
| T4.1 default 20 / max 100 | `EventListApiControllerTest.maximumPageSizeOf100IsAccepted`, `requestsWithoutASizeParameterUseTheDefaultPageSizeOf20` (added in T7.1) | test |
| T4.1 `nextCursor` only when more results exist | walk test (4 cursors then null), `aPageLengthExactlyEqualToSizeEndsTheWalk`, `aTieGroupEndingOnTheLastPageHasNullNextCursor`, `aCursorAtANonExistentPositionContinuesCorrectly` | test |
| T4.1 pagination walk: full dataset, no duplicates/gaps, tie groups | `paginationWalkTraversesAllEventsWithoutGapsOrDuplicates` (45 rows, page boundary through a 3-row tie) | test |
| T4.1 walk stable while a new event is inserted | `EventListApiControllerTest.cursorWalkIgnoresAnEventInsertedBetweenPages` | test |
| T4.1 one SELECT per page, no COUNT | `EventListApiControllerTest.aPageRequestIssuesExactlyOneSelectWithoutCount` (Hibernate statistics) | test |
| T4.1 the page SELECT really carries the SQL row cap | `EventListApiControllerTest.aPageSelectCarriesTheSqlLimitClause` (recorded statement text, added in T7.1 — statistics cannot detect a dropped LIMIT) | test |
| T4.2 malformed cursor → 400 (incl. all strict decoder rejections) | `EventListApiControllerTest.invalidQueryParametersReturn400ProblemDetails` (15 parameterized query cases) + `EventCursorTest.emptyCursorIsRejected` … `oversizedCursorIsRejected` (13 decode-rejection tests) | test |
| T4.2 invalid date range → 400, out-of-range bounds named | `invalidQueryParametersReturn400ProblemDetails` (occurredFrom/occurredTo cases), `occurredBoundsOutsideTheSupportedRangeAre400NamingTheParameter`, `cursorOccurredAtOutsideTheSupportedRangeIs400NamingTheParameter`; write-side twin: `EventApiControllerTest.occurredAtAtTheSupportedRangeBoundsIsAcceptedAndReplays`, `invalidRequestsReturn400ProblemDetailsWithFieldErrors` (range cases) | test |
| T4.2 OpenAPI parameter docs | `EventListApiControllerTest.openApiDocsDescribeTheListEndpoint` (all 7 parameters, 200/400, `EventPage`) | test |
| T4.2 documented in README | README §API → List events (GET): filters, cursor/nextCursor walk, error behaviour | doc |

### Stage 5 — X-API-Key security

| Criterion | Evidence | Kind |
|---|---|---|
| T5.1 missing/wrong key → 401 Problem Details, `WWW-Authenticate`, no key echo, nothing stored | `ApiKeySecurityTest.missingApiKeyIsRejectedWithProblemDetails401`, `wrongApiKeyIsRejectedWith401WithoutEchoingTheSubmittedKey` (wrong-key POST writes nothing) | test |
| T5.1 correct key → 201 write / 200 read (case-insensitive header) | `ApiKeySecurityTest.correctApiKeyAllowsWriteAndRead`, `lowerCaseHeaderNameAuthenticatesTheSameWay`, `duplicateApiKeyHeadersUseTheFirstValue` | test |
| T5.1 constant-time comparison, null/blank rules (now the per-source registry) | `ApiKeyRegistryTest` (14: parsing/validation, rotation overlap, deny-all, malformed fail-fast without digest echo, first-match source) + `ApiKeyMatcherTest` (5: SHA-256 helper pinned to the documented digests) | test (semantics only; constant-time is a code-level property, D14/D18) |
| T5.1 `prod` fails fast without a key | `ApiKeyProdRequirementTest.prodStartupFailsWithoutApiKeys`, `prodStartupFailsWithBlankApiKeys`, `prodStartupFailsWithWhitespaceOnlyApiKeys`, `prodStartupFailsWhenTheValueParsesToZeroEntries`, `prodStartupFailsWithAMalformedEntryNamingTheProperty`, `prodStartupSucceedsWithAValidEntry` | test |
| T5.1 health and the static demo page (`/demo/**`) public; every API/actuator/docs path requires the key | `ApiKeySecurityTest.actuatorHealthIsPublicWithoutApiKey`, `otherActuatorPathRequiresApiKey`, `apiDocsWithoutApiKeyIsRejectedWhileEnabledInTest`, `unknownPathWithoutKeyIsStillRejected`; the demo-page exception: `DemoStaticPageTest.demoPageIsServedWithoutApiKeyAndContainsTheExpectedElements` | test |
| T5.1 authenticated non-auth errors stay 404/405 (not 401); `/logout` answers 401 | `ApiKeySecurityTest.authenticatedRequestToUnknownPathReturns404NotAuthError`, `authenticatedUnsupportedMethodReturns405NotAuthError`, `logoutWithoutApiKeyReturns401Not302` | test |
| T5.1 Swagger/OpenAPI dev-profile-only; OpenAPI security scheme present | `ApiKeySecurityTest.openApiDocumentDeclaresApiKeySecurityScheme`; dev-profile enablement is configuration (`application-dev.yml`, base disables springdoc) — configuration review + stage-5 live probes | test + doc + live |
| T5.1 README "Authentication" | README §Authentication (this README) | doc |
| T5.1 (hardening) raw invalid percent-escape → 400 | `EventListApiControllerTest.rawInvalidPercentEscapeInQueryStringReturns400ProblemDetails` (round 4, F1) | test |

### Stage 6 — Demo package

| Criterion | Evidence | Kind |
|---|---|---|
| T6.1 client produces seeded events, usage documented | `SampleEventClientTest` (config/validation/exit codes/eventId scheme/payload determinism — non-network parts, 17 tests) + the two real run transcripts in README "Demo → Sample-event client" (fresh 6×201, same-batch replay 6×200) | test + live |
| T6.2 single-page demo verified in a browser; screenshot committed | screenshot `docs/images/demo.png` (real headless-Chrome run, README "Demo → Browser demo"); automated coverage of the server side: `DemoStaticPageTest.demoPageIsServedWithoutApiKeyAndContainsTheExpectedElements`, `demoPageCarriesNoSecrets`, `eventsApiStaysKeyProtectedWhileTheDemoPageIsPublic` | live + test |
| T6.3 deterministic dedupe script, one command, stable output, transcript in README | `scripts/dedupe-demo.sh` + the real 4-scenario transcript in README "Demo → Dedupe script"; run windows/parallel-rerun safety verified live in review round 5b (4 back-to-back runs, exit 0 each) | live |

### Project-level acceptance criteria

| Criterion | Evidence | Kind |
|---|---|---|
| Idempotent ingestion per `eventId` | T3.2 tests (200/409 semantics); `scripts/dedupe-demo.sh` end-to-end transcript (README) | test + live |
| Concurrent duplicates leave exactly one row | `EventApiControllerTest.concurrentIdenticalRequestsProduceExactlyOne201AndExactlyOneRow`; dedupe script scenario 1 (`1x201 + 9x200 … rows: 1`) | test + live |
| Stable keyset pagination | `paginationWalkTraversesAllEventsWithoutGapsOrDuplicates`, `cursorWalkIgnoresAnEventInsertedBetweenPages`, one-SELECT/no-COUNT + LIMIT tests | test |
| Simple service auth (D5, hardened by D18/D19) | `ApiKeySecurityTest` (14), `ApiKeyRegistryTest` (14), `ApiKeyMatcherTest` (5), `ApiKeyProdRequirementTest` (6), `RateLimiterTest` (8), `RateLimitPropertiesTest` (5), `RateLimitApiIntegrationTest` (2) | test |
| OpenAPI per contract | `openApiDocsDescribeTheEventsPath`, `openApiDocsDescribeTheListEndpoint`, `openApiDocumentDeclaresApiKeySecurityScheme` | test |
| Tests: full suite green on real PostgreSQL | `./mvnw verify` — latest run: 294 tests (M11.3; 275 at M11.2 close, 211 at the T7.2 sign-off); runs require only Docker (Testcontainers), not a pre-started database | test |
| Demo | three demo paths + README transcripts/screenshot (stage 6 rows above) | test + live |
| README evidence (T7.2) | this README (Getting started, API with captured responses, Authentication, Demo transcripts/screenshot, Testing & evidence pointing here); orchestrator follows it literally on a clean checkout | doc + live |

### Known evidence gaps (recorded, not silently omitted)

- **Getting-started/compose path is manual evidence.** `docker compose config`, the compose
  healthcheck and the literal README walk-through are live probes (stage-1 report, T7.2
  acceptance), not JUnit tests — automating them would mean Docker-in-test orchestration of the
  compose stack, which the approved scope does not include. The automated suite instead proves
  the same stack behaviour directly via Testcontainers (same pinned image).
- **Browser-demo interactivity has no automated UI drive.** Filters, the cursor "Load more"
  walk, the send-event form and the round-5 adversarial XSS probe were verified live (manually,
  review rounds 5/5b); `DemoStaticPageTest` only pins serving, element presence, no-secret
  embedding and API protection. Closing this needs a browser-driver framework — deferred as out
  of scope.
- **Dedupe-script evidence is a transcript, not a test.** `scripts/dedupe-demo.sh` is shell
  logic (docker/psql/HTTP races), reproducible by one command, but not part of
  `./mvnw verify`; its failure handling is documented, and its run id is per-process so reruns
  stay valid (round 5, F1).
- **One-off live probes from review rounds** (e.g. the 2d totality checks over number literals,
  the replicated 401/401/201 pattern probes) remain recorded in the review log and decisions
  D10/D13/D14 rather than converted to tests; their load-bearing behavior is covered by the
  automated tests named above.

## Review log

- **Round 1** (2026-10-18, DeepSeek V4.1 Flash, read-only, stages 0–2): verdict **"sound with minor
  issues"**. Major: F1 inert `test` profile; F2 production BigDecimal hash path unproven by tests.
  Minor/nit: F3 UTF-8 proof, F4 `occurredAt` precision mismatch, F5 missing edge tests, F6 Jackson
  2/3 coexistence note, F7 non-existent full-stack compose command, F8 README log excerpt, F9
  present-tense overclaims, F10 duplicate timestamp columns. Closure: F1–F5 in commit `1b408d8`
  (30 tests green, verified by the orchestrator), F6–F9 in `0374c5f`, F10 documented in D11. Two
  forward-looking notes were accepted into Stage 3: the insert must run in its own transaction
  boundary, and oversized metadata numbers must map to 400.
- **Round 2** (2026-10-18, DeepSeek V4.1 Flash, read-only, Stage 3): verdict **"not sound"**.
  F1 accepted metadata numbers (`1e1000`) became unreadable (500 on replay); F2 unbounded exponent
  (OOM/DoS); F3 client SQL data errors returned 500; F4 201/200 numeric rendering mismatch; F5
  DB read-path precision unasserted; F6–F9 docs/nits. Fixes `0247667`, `a724dbf`.
- **Round 2b** (2026-10-18, Stage 3 fixes): verdict **"not sound"**. B1 trailing-zero scale above
  the 4096 read limit (500 on insert); B2 int overflow in digit arithmetic (OOM/500). Fixes
  `39481cd`, `07895d2`.
- **Round 2c** (2026-10-18, B1/B2 fixes): verdict **"not sound"**. F1 residual:
  `stripTrailingZeros` overflow (`100e2147483647` → 500); F2/F3 wording; F4 stale README; F5
  negative boundary untested. Fixes `bf13ddc`, `9f584a8`.
- **Round 2d** (2026-10-18, sign-off): verdict **"sound with minor issues"** (docs-only wording
  residuals; optional nits). Fixed in `93c5c84`; F1 totality proven (299 live/programmatic checks,
  3343-value guard cross-check); 108 tests green (orchestrator-run). **Stage 3 signed off.**
- **Round 3** (2026-10-18, DeepSeek V4.1 Flash, read-only, Stage 4): verdict **"not sound"**.
  F1 cursor encode bound (512) below the worst legal cursor (1104): emitted-then-rejected tokens
  broke walks for control-character eventIds; F4 GET range/NUL errors surfaced a POST-oriented
  detail; F2/F3/F5/F6/F7 doc/test nits; README API placeholder. Fixes `fc1c03a`, `b52174a`.
- **Round 3b** (2026-10-18, Stage 4 fixes): verdict **"sound with minor issues"**, not signed off:
  F-1 residual encode>decode range asymmetry (write-legal occurredAt outside the read range),
  L1/L2 doc precision, L3 local legacy rows, I1 attribution. Fixes `9cc2dd4`, `e8612b1`, plus
  `66f8337` (doc precision) and local-volume cleanup.
- **Round 3c** (2026-10-18, sign-off): verdict **"sound with minor issues"**, conditional sign-off
  pending L1/L2 doc precision, L3 legacy-row hygiene and the I1 attribution; no behavioral
  findings, 164 tests green. Conditions met in `66f8337`; local out-of-range legacy rows deleted.
  **Stage 4 signed off.**
- **Round 4** (2026-10-18, DeepSeek V4.1 Flash, read-only, Stage 5): verdict **"sound with minor
  issues"**, nothing blocking. F1 raw invalid percent-escapes returned 500 (pre-existing Stage 4
  gap), F2 `/logout` answered 302 without a key, F3 whitespace in `API_KEY` caused a silent
  outage, F4 the Swagger UI needs a header-capable client; plus test/javadoc nits. Fixes
  `eddb75d`, `6842584`.
- **Round 4b** (2026-10-18, sign-off): verdict **"sound with minor issues"** — all round-4
  findings and nits verified closed, 187 tests green, live probes reproduce the behaviour; only
  documentation nits remained (D14 count, review log, two javadocs), fixed in `74b1d56`.
  **Stage 5 signed off.**
- **Round 5** (2026-10-18, DeepSeek V4.1 Flash, read-only, Stage 6): verdict **"not sound"**,
  solely because of F1 (the dedupe script's second-resolution run id collided on back-to-back
  runs, producing a misleading failure); client determinism, the demo page (incl. an adversarial
  XSS probe), security scoping, tests and evidence were all sound. F2-F12 were minor/nits.
  Fixes `41b7712`, `ad7fc09`.
- **Round 5b** (2026-10-18, sign-off): verdict **"sound"** — all F1-F12 verified closed with live
  evidence (4 back-to-back + 2 overlapping script runs, client exit codes 2/1/1, page
  filter-snapshot behaviour), 207 tests green; two cosmetic script notes folded into this
  sign-off commit. **Stage 6 signed off.**
- **Round 6** (2026-10-18, DeepSeek V4.1 Flash, read-only, Stage 7): verdict **"sound with minor
  issues"** — no blocker; F1 (major, docs): the "health-only public" wording ignored the
  intentionally public static demo page `/demo/**`; F2–F5: malformed-JSON error shape, an
  unevidenced duplicate-header claim, the unexplained "OTAP" sentence and a missing T4.2 matrix
  row; F6–F9 nits (DB_PORT in step 1, wording, evidence labels). All closed: README/D14/matrix
  corrections, a malformed-JSON test and a duplicate-header test (211 tests). **Stage 7 signed
  off.**
- **Round 7** (2026-10-19, DeepSeek V4.1 Flash, read-only, whole repository, Stage 8 T8.1):
  verdict **"sound with minor issues"**, no blocker, ready for handover once the sign-off commit
  lands its minor items. A: a stale `ApiKeySecurityTest` count in the matrix (12 → 13); B: D14's
  public-surface bullet and the README D5 summary still implied health-only public (the static
  `/demo/**` page is intentionally public, D15); C: the owner's `.gitignore` addition was still
  uncommitted (committed separately for a clean checkout); D–F nits: "health public" wording,
  no `LICENSE` (owner's call) and an optional `isUniqueEventIdViolation` full-chain-scan
  hardening (no current failure path — deferred). All prior review findings (rounds 1–6) were
  re-verified as closed. **Project handed over for the owner's final acceptance.**
