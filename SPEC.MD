# Mini Wallet – Specification

## Data model (Flyway V1)

| Table | Key columns |
|---|---|
| `wallets` | `id` (uuid), `owner_id`, `currency` (default `IDR`), `balance` bigint `CHECK (balance >= 0)`, `version`, `created_at` |
| `transactions` | `id` (uuid), `type` (`TOPUP`/`TRANSFER`), `status`, `idempotency_key`, `request_hash`, `owner_id`, `created_at`; **unique (`owner_id`, `idempotency_key`)** |
| `ledger_entries` | `id`, `transaction_id`, `wallet_id`, `direction` (`DEBIT`/`CREDIT`), `amount` bigint `CHECK (amount > 0)`, `balance_after`, `created_at` |
| `outbox_events` (M4) | `id`, `aggregate_id`, `type`, `payload` jsonb, `created_at`, `published_at` |

Top-ups are modelled as a transfer from a system "cash-in" wallet, so the ledger always balances.

## API (v1)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/wallets` | create a wallet |
| GET | `/api/v1/wallets/{id}` | balance |
| POST | `/api/v1/wallets/{id}/topups` | requires `Idempotency-Key` |
| POST | `/api/v1/transfers` | `{fromWalletId, toWalletId, amount}`, requires `Idempotency-Key` |
| GET | `/api/v1/wallets/{id}/transactions` | paginated ledger history |

## Behaviour to get right
- Transfer to self is rejected (400).
- Insufficient funds returns 422 `INSUFFICIENT_FUNDS`; nothing is written.
- Retry with same `Idempotency-Key` + same body returns the SAME response (HTTP 200, same transaction id), no new ledger entries.
- Same key + different body returns 422 `IDEMPOTENCY_KEY_REUSED`.
- Two concurrent requests with the same key: exactly one is applied (rely on the unique constraint, handle the violation).
- Opposite concurrent transfers (A→B and B→A) never deadlock.

## Milestones

**M1 – Core.** Project skeleton, Docker Compose, Flyway V1, wallet CRUD, top-up, transfer, ledger, error handling, OpenAPI.

**M2 – Correctness proof.** Testcontainers integration tests:
- 100 concurrent transfers out of one wallet never overdraw it
- A→B / B→A concurrent transfers complete without deadlock
- idempotent retry creates no extra ledger rows
- invariant test: for random operations, total money in system is constant and each wallet balance equals the sum of its ledger entries
Then a k6 load test; record requests/sec and p95 latency in the README.

**M3 – Security.** Spring Security with JWT; users can only move money out of wallets they own; rate limiting on transfers.

**M4 – Events.** Transactional outbox: write an `outbox_events` row in the same DB transaction as the transfer; a scheduled publisher sends to Kafka with at-least-once delivery; a consumer demonstrates idempotent handling (e.g. notification log).

**M5 – Polish.** Micrometer metrics (transfer count, failures, latency) with a Grafana dashboard in Compose, architecture diagram, `docs/DESIGN_DECISIONS.md`, README with load-test numbers and a "how to run in one command" section.

## Definition of done
- `docker compose up` starts everything; README shows curl examples
- Tests prove the invariants above
- README states real measured numbers, not guesses