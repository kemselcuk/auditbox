# AuditBox — Agent Guidelines

AuditBox is a small service that accepts transaction/event history over HTTP, stores it in
PostgreSQL, and serves it back with filters and stable pagination. Single service, single
`events` table.

## Language

- ALL repository content must be in English: code, identifiers, comments, commit messages, docs, README.
- The repository owner speaks Turkish. Converse with the owner in Turkish.

## Scope boundaries (do not expand)

- No Kafka, no event platform, no additional services or tables beyond `events`.
- The service records what it receives; it must NOT be described as immutable or tamper-proof.
  Keep the "what this is / is not" boundary explicit in docs.
- Video is out of scope for now.

## Stack

- Java 21 LTS (Temurin 21 is the machine default; `maven.compiler.release=21`), Maven wrapper.
- Spring Boot 4.1.x (latest patch).
- PostgreSQL via Docker Compose; Flyway migrations; Spring Data JPA; Bean Validation.
- Spring Security with a static `X-API-Key`; springdoc-openapi for API docs.
- Tests: JUnit 5 + Testcontainers against the real PostgreSQL image.

## Commands

- Build and test: `./mvnw verify`
- Start database: `docker compose up -d db` (host port 5432; set `DB_PORT` if it is occupied)
- Run app locally: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`

## Working rules

- Keep one small task per commit on `main`; commit messages in English, imperative mood,
  Conventional Commits style. The orchestrator commits after verification.
- Never claim a task is done without running the acceptance commands and pasting the real output.
- Update `docs/plan.md` status when a task completes; record decisions in `docs/decisions.md` (D1, D2, ...).
- No secrets in the repository; configuration comes from environment variables (see `.env.example`).

## Documentation map

- `README.md` — usage, integration, decisions summary, real test/demo evidence.
- `docs/plan.md` — stages, tasks, acceptance criteria, status, evidence matrix.
- `docs/decisions.md` — decision log with rationale.
