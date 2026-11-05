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
  database transaction. A top-up is a transfer from one of 16 system cash-in wallets, so the ledger always balances.
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
isolation behave like production. Docker must be running. They include:

- 100 parallel transfers out of one wallet never overdraw it (through the service and over HTTP)
- opposite transfers (A to B and B to A) and a 3-wallet ring do not deadlock
- 20 parallel requests with the same key apply exactly once; replays add no rows anywhere
- a seeded random test: 1,000 overlapping top-ups, transfers and retries on 16 threads, then the books must balance
  (replay a failure with `./mvnw verify -Dinvariant.seed=<seed>`)

The tests were themselves tested: [DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md#3-how-we-know-the-tests-can-actually-fail-mutation-experiments)
shows which tests fail when the locking, the unique constraint or the ledger writes are deliberately broken.

## Performance

Measured with [k6](load-test/README.md) against the real HTTP API: 32 concurrent users sending requests back to back,
15 s warm-up (excluded) then 60 s measured, three runs per scenario. The table shows the median run.
About 5% of requests are retries with the same `Idempotency-Key` (answered from the stored result).

| Scenario (database pool of 10) | Requests/sec | p95 latency | p99 latency | Errors (5xx) |
|---|---|---|---|---|
| **Spread**: random transfers between 200 wallets | **1,795** | **28 ms** | 36 ms | 0 |
| **Hot wallet**: every transfer pays into or out of one wallet | 347 | 131 ms | 151 ms | 0 |
| **Top-ups, before**: all lock one cash-in wallet | 330 | 139 ms | 161 ms | 0 |
| **Top-ups, after**: spread over 16 cash-in wallets | **1,625** | **30 ms** | 39 ms | 0 |

Run-to-run spread was small (spread: 1,780-1,850 req/s; hot: 346-351; top-ups before: 326-335, after: 1,612-1,632).
After every run [`verify.sql`](load-test/verify.sql) confirmed the books balance (no negative balance, total money
unchanged, every balance equal to its ledger, every transaction balanced): 0 violations.

### Two bottlenecks found and measured

**1. Top-ups queued on a single row (fixed: 4.9x).** The first load test showed top-ups at ~350 req/s against ~1,800
for transfers. Every top-up locked the one system cash-in wallet, so all top-ups in the system took turns on it.
Unlike the hot-wallet case, which is a real limit (many people paying one wallet), this was a design choice. There are
now 16 cash-in wallets and a top-up picks one from its idempotency key. Top-ups went from 330 to 1,625 req/s, p95 from
139 ms to 30 ms, and the 16 wallets were used evenly (each paid for 6.2-6.3% of the top-ups). Locking order, and
therefore deadlock freedom, is unchanged. Details and rejected alternatives: [DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md#4-spreading-top-ups-over-16-cash-in-wallets).

**2. The database connection pool.** With 32 users and a pool of 10 connections, requests were waiting for a
connection. Same code, fresh database each time (the app confirms the pool size it runs with):

| Database pool size | Spread: req/s (p95) | Top-ups: req/s (p95) |
|---|---|---|
| 10 (default) | 1,795 (28 ms) | 1,625 (30 ms) |
| 20 | 2,834 (18 ms) | 2,372 (23 ms) |
| 32 | 3,510 (16 ms) | 2,705 (26 ms) |

So on this machine the default pool, not the locking, limited the normal case: 32 connections nearly doubled
throughput. The default stays 10 here: the right size depends on how many CPU cores the database has, its
`max_connections`, and how many app instances share it. Change it with `DB_POOL_SIZE` (see [load-test](load-test/README.md)).

**What the numbers say**
- When requests touch different wallets they run in parallel and the service handles ~1,800 req/s (up to ~3,500 with
  a bigger pool).
- When every request needs the same wallet, they must take turns on that row lock, so throughput drops about 5x to
  ~350 req/s (one transaction every ~2.9 ms). That is the price of the locking rule that makes overdrafts and lost
  updates impossible, and it is a property of the workload, not something to remove.
- Latency follows from throughput (32 users / 347 req/s = 92 ms average, which is what was measured).
- At a pool of 32, top-ups (2,705) stay below spread (3,510) and their p99 is higher. My explanation is that 32
  concurrent top-ups over 16 cash-in wallets sometimes pick the same wallet and wait; I have not tested that (for
  example with 32 or 64 cash-in wallets).

**What they do not say.** One development laptop (Intel i7-10700, 16 GB, Windows 11, Docker Desktop) ran the app,
PostgreSQL and k6 together, with an IDE and browser open. Users send their next request as soon as the last one
returns (no think time), so this shows the saturation point of this setup, not a production capacity. With a bigger
pool the database and the app also compete for the same CPU. The hot-wallet figure and the first top-up measurement
come from an earlier session; the top-up re-run was 5% lower (330 against 349), which is the size of the drift between
sessions. Only the pool-10 numbers for spread and top-ups were measured back to back with the final code.
Raw results, machine details and the books checks are in [load-test/results](load-test/results).

## Roadmap

- **M2** – done: seeded random-operations invariant test, 100-way concurrency tests, mutation experiments
  ([docs](docs/DESIGN_DECISIONS.md)) and the load test above
- **M3** – Spring Security + JWT, ownership checks, rate limiting
- **M4** – transactional outbox + Kafka
- **M5** – metrics dashboard, architecture diagram, load-test results
