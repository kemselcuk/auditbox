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
- Status: partially confirmed; finalized with T1.1/T1.2 evidence.
