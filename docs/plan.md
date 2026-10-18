# AuditBox — Plan

Owner-approved 2026-10-18. Executed in small tasks; no stage starts before the previous stage's
tests are green.

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
| T3.1 | DTO + Bean Validation; 201/200/409 semantics; Problem Details error model with field errors; OpenAPI schema | Validation tests green (400 on bad payload with field details) | pending |
| T3.2 | Insert → catch unique violation → re-read → compare hash → 200 or 409 (with stored record summary) | Integration tests on real PostgreSQL: new → 201; identical repeat → 200 and exactly one row; different content → 409 with detail; **N concurrent identical requests → exactly one row, responses only {1×201, rest 200}, no 5xx**; concurrent different content → 1×201 + 1×409 | pending |

### Stage 4 — GET /api/v1/events (filters + keyset pagination)

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T4.1 | Filters `userId`, `eventType`, `source`, `occurredAt` from/to; order `occurred_at DESC, event_id DESC`; opaque base64 cursor; default 20 / max 100; `nextCursor` only when more results exist | Filter combination tests green; **pagination walk test: full dataset traversed with no duplicates or gaps, including rows sharing the same `occurred_at`**; walk stays stable while a new event is inserted | pending |
| T4.2 | Malformed cursor → 400; invalid date range → 400; OpenAPI parameter docs | Negative tests green; documented in README | pending |

### Stage 5 — X-API-Key security

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T5.1 | Stateless Spring Security + API key filter; constant-time comparison; missing/wrong key → 401 (Problem Details); `prod` fails fast without a key; health public; Swagger dev-profile only | Security tests green (401/401/201 pattern); README "Authentication"; OpenAPI security scheme present | pending |

### Stage 6 — Demo package

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T6.1 | Small Java client (JDK HttpClient, no extra dependencies) generating N sample events | Client run against the local app produces seeded events; usage documented | pending |
| T6.2 | Single-page web demo (vanilla HTML+JS served by the app): API key input, filters, table, "load more" cursor, send-event form | Verified in a browser by the orchestrator (screenshot into `docs/images/`); README steps reproduce it | pending |
| T6.3 | Deterministic dedupe script: concurrent identical eventId submissions (HTTP status distribution + DB row count) and a different-content 409 case; one command, stable output | Script output embedded in README as a real transcript | pending |

### Stage 7 — Test hardening and README

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T7.1 | Acceptance criteria → test/evidence matrix in this file; gaps closed | `./mvnw verify` fully green; every criterion mapped to a test or demo evidence | pending |
| T7.2 | README: Overview, What this is / is not, Architecture, API examples, Decisions summary, Getting started, Demo (real outputs), Testing & evidence, Next steps | Orchestrator follows README literally on a clean checkout and succeeds; claims match evidence | pending |

### Stage 8 — Independent final review and handoff

| Task | Deliverable | Acceptance criteria | Status |
|---|---|---|---|
| T8.1 | Read-only full-diff and docs review round (DeepSeek V4.1 Flash) | Findings resolved or explicitly deferred with rationale | pending |
| T8.2 | Final acceptance pass + cost report | Acceptance checklist with evidence; owner gives final acceptance | pending |

## Acceptance evidence matrix

Filled in T7.1: each acceptance criterion mapped to its test/evidence artefact.
