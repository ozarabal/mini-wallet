# Mini Wallet – project rules for Claude Code

A backend service for digital wallets: top-up, peer-to-peer transfer, and a double-entry ledger.
Goal: a portfolio project that demonstrates correctness under concurrency and failure.
The owner must be able to explain every line in an interview, so favour clear, simple code over clever code.

## Stack
- Java 21, Spring Boot 3.5.x, Maven
- PostgreSQL 16, Spring Data JPA, Flyway (schema changes ONLY via migrations; never `ddl-auto=update`)
- Validation (Jakarta), springdoc-openapi, Micrometer + Prometheus
- Tests: JUnit 5, Testcontainers (real PostgreSQL, no H2), AssertJ
- Later milestones: Spring Security + JWT, Kafka (transactional outbox), k6 load test
- Docker Compose for local run (app + postgres)

## Package layout (by feature, not by layer)
`com.rafif.wallet`
- `wallet` – Wallet entity, repository, service, controller
- `ledger` – LedgerEntry, Transaction entities
- `transfer` – transfer + top-up use cases, idempotency
- `common` – error handling, money type, config

## Non-negotiable rules
1. Money is `long` in minor units (IDR has no decimals). Never `double`/`float`. Never negative amounts.
2. Every balance change writes ledger entries in the SAME database transaction. For every transaction, sum(debits) == sum(credits).
3. Wallet balance never goes below zero (enforced in code AND by a `CHECK (balance >= 0)` constraint).
4. Transfers lock both wallets with `SELECT ... FOR UPDATE` in ascending wallet-id order (prevents deadlocks).
5. Every write endpoint requires an `Idempotency-Key` header. Same key + same payload returns the original result. Same key + different payload returns 422. Never apply a request twice.
6. No business logic in controllers. Controllers validate and delegate to services.
7. Use DTO records for API input/output; never expose JPA entities.
8. Errors use RFC 7807 `ProblemDetail` with stable error codes (e.g. `INSUFFICIENT_FUNDS`, `WALLET_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`).
9. Every feature ships with tests. A concurrency feature ships with a concurrency test.
10. Keep commits small, one logical change each. Explain WHY in design notes, not just what.

## Working style
- Before coding each milestone, write a short plan and wait for approval.
- After each milestone, run `./mvnw verify` and report results honestly. Do not claim tests pass without running them.
- When a design choice has trade-offs (locking strategy, isolation level), add a short entry to `docs/DESIGN_DECISIONS.md` with the alternatives considered.
- Ask before adding any dependency not listed above.