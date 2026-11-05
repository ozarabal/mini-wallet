# mini-wallet

A backend service for digital wallets: **top-up**, **peer-to-peer transfer** and a **double-entry ledger**.
The goal is correctness under concurrency and failure: no overdrafts, no deadlocks, no double-applied requests.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Testcontainers

## Run it

```bash
docker compose up --build
```

The API is on http://localhost:8080 and the interactive docs (Swagger UI) on http://localhost:8080/swagger-ui.html.
Health: `/actuator/health` · Prometheus metrics: `/actuator/prometheus`.

## Try it with curl

Amounts are integers in minor units (IDR has no decimals). Every write needs an `Idempotency-Key` header.

```bash
# 1. create two wallets (note the "id" in each response)
curl -s -X POST localhost:8080/api/v1/wallets -H 'Content-Type: application/json' -d '{"ownerId":"alice"}'
curl -s -X POST localhost:8080/api/v1/wallets -H 'Content-Type: application/json' -d '{"ownerId":"bob"}'

ALICE=<alice wallet id>
BOB=<bob wallet id>

# 2. top up alice with 100000
curl -s -X POST localhost:8080/api/v1/wallets/$ALICE/topups \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: topup-1' -d '{"amount":100000}'

# 3. transfer 25000 from alice to bob
curl -s -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: transfer-1' \
  -d "{\"fromWalletId\":\"$ALICE\",\"toWalletId\":\"$BOB\",\"amount\":25000}"

# 4. repeat step 3 exactly: HTTP 200, the same transactionId, and no money moves a second time

# 5. check balances and history (newest first, paginated)
curl -s localhost:8080/api/v1/wallets/$ALICE
curl -s "localhost:8080/api/v1/wallets/$ALICE/transactions?page=0&size=20"
```

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/wallets` | create a wallet (balance starts at 0) |
| GET | `/api/v1/wallets/{id}` | balance |
| POST | `/api/v1/wallets/{id}/topups` | `{amount}`, requires `Idempotency-Key` |
| POST | `/api/v1/transfers` | `{fromWalletId, toWalletId, amount}`, requires `Idempotency-Key` |
| GET | `/api/v1/wallets/{id}/transactions` | paginated ledger history, `page` (from 0) and `size` (1-100) |

Write endpoints return **201** the first time and **200** with the identical body when the same request is retried.

Errors are [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) problem documents with a stable `code`:

| HTTP | code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` | bad input |
| 400 | `IDEMPOTENCY_KEY_MISSING` | write without the header |
| 400 | `SELF_TRANSFER` | from and to are the same wallet |
| 404 | `WALLET_NOT_FOUND` | unknown wallet |
| 422 | `INSUFFICIENT_FUNDS` | balance too low; nothing is written |
| 422 | `IDEMPOTENCY_KEY_REUSED` | same key, different request |

## How correctness is guaranteed

- **Money is a `long` in minor units.** No floating point, so no rounding errors.
- **Double-entry ledger.** Every balance change writes a DEBIT and a CREDIT entry of equal amount in the *same*
  database transaction. A top-up is a transfer from a system cash-in wallet, so the ledger always balances.
- **No negative balances**, enforced in code and by a `CHECK (balance >= 0)` constraint.
- **Ordered locking.** A transfer locks both wallets with one `SELECT ... ORDER BY id FOR UPDATE`. Everyone locks in
  the same order, so A→B and B→A at the same time cannot deadlock.
- **Idempotency via a database unique constraint** on `(owner_id, idempotency_key)`. When two identical requests
  race, the database lets exactly one win and the other returns the winner's result.

The reasoning and the alternatives that were rejected are in [docs/DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md).

## Tests

```bash
./mvnw verify
```

Integration tests run against a **real PostgreSQL 16** in Testcontainers (no H2), so locking, constraints and
isolation behave like production. They include concurrency tests: parallel transfers out of one wallet never
overdraw it, opposite transfers do not deadlock, and 20 parallel requests with the same key apply exactly once.
Docker must be running.

## Roadmap

- **M2** – full concurrency proof suite (invariant test) and a k6 load test with measured numbers
- **M3** – Spring Security + JWT, ownership checks, rate limiting
- **M4** – transactional outbox + Kafka
- **M5** – metrics dashboard, architecture diagram, load-test results
