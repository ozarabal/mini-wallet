# mini-wallet

A backend service for digital wallets: **top-up**, **peer-to-peer transfer** and a **double-entry ledger**.
The goal is correctness under concurrency and failure: no overdrafts, no deadlocks, no double-applied requests.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Testcontainers

## Run it

```bash
docker compose up --build
```

This starts PostgreSQL, a single-node Kafka (KRaft, no ZooKeeper) and the app.
The API is on http://localhost:8080 and the interactive docs (Swagger UI) on http://localhost:8080/swagger-ui.html.
Health: `/actuator/health` · Prometheus metrics: `/actuator/prometheus`.

## Try it with curl

Amounts are integers in minor units (IDR has no decimals). Every write needs an `Idempotency-Key` header, and every
call except register/login needs `Authorization: Bearer <token>`.

```bash
# 1. register two users and log in (the token is valid for 15 minutes)
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-password-1"}'
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bob-password-123"}'

ALICE_TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-password-1"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
BOB_TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bob-password-123"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')

# 2. each user creates a wallet (no body: the owner is whoever the token says). Note the "id" in each response.
curl -s -X POST localhost:8080/api/v1/wallets -H "Authorization: Bearer $ALICE_TOKEN"
curl -s -X POST localhost:8080/api/v1/wallets -H "Authorization: Bearer $BOB_TOKEN"

ALICE=<alice wallet id>
BOB=<bob wallet id>

# 3. top up alice's wallet with 100000
curl -s -X POST localhost:8080/api/v1/wallets/$ALICE/topups \
  -H "Authorization: Bearer $ALICE_TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: topup-1' -d '{"amount":100000}'

# 4. transfer 25000 from alice to bob
curl -s -X POST localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $ALICE_TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: transfer-1' \
  -d "{\"fromWalletId\":\"$ALICE\",\"toWalletId\":\"$BOB\",\"amount\":25000}"

# 5. repeat step 4 exactly: HTTP 200, the same transactionId, and no money moves a second time

# 6. check balances and history (newest first, paginated). Only the owner can read a wallet.
curl -s localhost:8080/api/v1/wallets/$ALICE -H "Authorization: Bearer $ALICE_TOKEN"
curl -s "localhost:8080/api/v1/wallets/$ALICE/transactions?page=0&size=20" -H "Authorization: Bearer $ALICE_TOKEN"

# 7. bob tries to spend alice's money: HTTP 403, code NOT_WALLET_OWNER, nothing changes
curl -s -X POST localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $BOB_TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: steal-1' \
  -d "{\"fromWalletId\":\"$ALICE\",\"toWalletId\":\"$BOB\",\"amount\":1}"
```

In Swagger UI use the **Authorize** button and paste the token from login.

## Security

- **Login and tokens.** `POST /api/v1/auth/register` and `/login` are the only public write endpoints. Passwords are
  stored as BCrypt hashes. Login returns a signed JWT (HS256, 15 minutes, issuer `mini-wallet`); the app refuses to
  start without a `JWT_SECRET` of at least 32 bytes. `docker-compose.yml` has a dev-only default; set your own for
  anything real. Wrong username and wrong password give the same 401, and take about the same time.
- **Every other endpoint needs a valid token**: 401 `UNAUTHENTICATED` (no token) or `TOKEN_INVALID` (bad, expired,
  forged, unsigned, wrong issuer or algorithm).
- **Ownership.** The owner of a wallet is taken from the token, never from the request. Only the owner can read a
  wallet, read its history, top it up or spend from it (403 `NOT_WALLET_OWNER`). Anyone can pay *into* a wallet.
  The 16 system cash-in wallets belong to `SYSTEM`, so no user can touch them.
- **Rate limit.** `POST /api/v1/transfers` is limited per user with a token bucket (burst 20, 10 per second):
  429 `RATE_LIMITED` with a `Retry-After` header. Configure under `wallet.rate-limit.transfers`.

Design reasons, trade-offs and known gaps (no refresh tokens or revocation, no login brute-force protection, the rate
limit lives in one instance's memory): [DESIGN_DECISIONS.md section 7](docs/DESIGN_DECISIONS.md#7-authentication-ownership-and-rate-limiting-m3).

## Events: transactional outbox and Kafka

Every completed top-up or transfer is announced on the Kafka topic `wallet.transactions`, without ever announcing
something that did not happen or forgetting something that did.

- **The event is written in the same database transaction as the money movement** (an `outbox_events` row). Either
  both are committed or neither is, so there is no moment where the money moved but the event is lost, or the other
  way round. Writing to Kafka directly from the request could not guarantee that: the two systems cannot commit together.
- **A background publisher sends the rows to Kafka**, oldest first, and marks a row as published only after the broker
  acknowledged it (`acks=all`, idempotent producer). If the app dies in between, the event is sent again: delivery is
  **at-least-once** (duplicates possible, loss not). Only one publisher runs at a time, across all instances, using a
  database advisory lock.
- **Ordering.** A message is keyed by the wallet that pays (a top-up: the wallet that receives), so the events of one
  wallet arrive in the order they happened. There is no ordering guarantee *between* wallets.
- **Consumers must ignore duplicates.** The example consumer in this app writes each event to `notification_log`
  with a unique `event_id` (`INSERT ... ON CONFLICT DO NOTHING`), so a repeated delivery changes nothing. If the
  database is down it retries the same message until it works. An unreadable message is skipped and logged.
- Messages contain no user or owner ids (they cannot be taken back once in Kafka).

To watch it work after the curl flow above:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic wallet.transactions --from-beginning --property print.key=true
docker compose exec postgres psql -U wallet -d wallet -c "SELECT type, amount, received_at FROM notification_log"
```

Settings are under `wallet.outbox` and `wallet.notification`; the broker is at `localhost:29092` from your machine
(no authentication: local development only). Reasons, alternatives, ordering caveats and known gaps:
[DESIGN_DECISIONS.md section 9](docs/DESIGN_DECISIONS.md#9-transactional-outbox-and-kafka-m4).
**The cost is real: about 20-30% of throughput in the load test** (see Performance below).

## API

All endpoints except register/login need `Authorization: Bearer <token>`.

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/auth/register` | `{username, password}`; public. 201, or 409 `USERNAME_TAKEN` |
| POST | `/api/v1/auth/login` | `{username, password}`; public. Returns `{accessToken, tokenType, expiresInSeconds}` |
| POST | `/api/v1/wallets` | create a wallet owned by the caller (no body; balance starts at 0) |
| GET | `/api/v1/wallets/{id}` | balance (owner only) |
| POST | `/api/v1/wallets/{id}/topups` | `{amount}`, requires `Idempotency-Key` (owner only) |
| POST | `/api/v1/transfers` | `{fromWalletId, toWalletId, amount}`, requires `Idempotency-Key`; you must own the source wallet; rate limited |
| GET | `/api/v1/wallets/{id}/transactions` | paginated ledger history (owner only), `page` (from 0) and `size` (1-100) |

Write endpoints return **201** the first time and **200** with the identical body when the same request is retried.

Errors are [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) problem documents with a stable `code`:

| HTTP | code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` | bad input |
| 400 | `IDEMPOTENCY_KEY_MISSING` | write without the header |
| 400 | `SELF_TRANSFER` | from and to are the same wallet |
| 401 | `UNAUTHENTICATED`, `TOKEN_INVALID` | no token / bad or expired token |
| 401 | `INVALID_CREDENTIALS` | wrong username or password at login |
| 403 | `NOT_WALLET_OWNER` | the wallet belongs to someone else (or the system) |
| 404 | `WALLET_NOT_FOUND` | unknown wallet |
| 409 | `USERNAME_TAKEN` | registering a name that exists |
| 422 | `INSUFFICIENT_FUNDS` | balance too low; nothing is written |
| 422 | `IDEMPOTENCY_KEY_REUSED` | same key, different request |
| 429 | `RATE_LIMITED` | too many transfers; see `Retry-After` |

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

Integration tests run against a **real PostgreSQL 16** (and, for the Kafka tests, a **real Kafka broker**) in Testcontainers (no H2), so locking, constraints and
isolation behave like production. Docker must be running. They include:

- requests without a valid token, or for someone else's wallet, are refused and change nothing (including 100 simultaneous thieves)
- the rate limiter admits exactly the burst under 100 simultaneous requests
- 100 parallel transfers out of one wallet never overdraw it (through the service and over HTTP)
- opposite transfers (A to B and B to A) and a 3-wallet ring do not deadlock
- 20 parallel requests with the same key apply exactly once; replays add no rows anywhere
- every top-up and transfer produces exactly one outbox event, in order per wallet, rolled back with the money; a duplicated Kafka delivery is processed once; a database outage makes the consumer retry, not skip
- a seeded random test: 1,000 overlapping top-ups, transfers and retries on 16 threads, then the books must balance
  (replay a failure with `./mvnw verify -Dinvariant.seed=<seed>`)

The tests were themselves tested: [DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md#3-how-we-know-the-tests-can-actually-fail-mutation-experiments)
shows which tests fail when the locking, the unique constraint or the ledger writes are deliberately broken; [section 6](docs/DESIGN_DECISIONS.md#6-security-mutation-experiments-ownership-tokens-rate-limit-registration) does the same for ownership, tokens, the rate limit and registration, and [section 8](docs/DESIGN_DECISIONS.md#8-outbox-and-kafka-mutation-experiments-m4) for the outbox, publisher and consumer (one mutant survived and led to a new test).

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

### After adding login, ownership checks and the rate limit (M3)

Every request now carries a JWT that is verified (HMAC signature, expiry, issuer), every transfer or top-up loads the
wallet and checks its owner, and transfers pass through the per-user rate limiter. The load test was changed to match:
20 registered users each own 10 wallets, and each virtual user sends its own user's token. The rate limit was raised to
1,000,000 for the test, otherwise it would (correctly) throttle the test. Same machine, same settings, database pool of
10, fresh database for each of three runs per scenario; median shown, before = the numbers above.

| Scenario | Before (no auth) | After (auth + ownership + limiter) | Change |
|---|---|---|---|
| Spread | 1,795 req/s, p95 28 ms | **1,709 req/s**, p95 29 ms | -5% |
| Hot wallet | 347 req/s, p95 131 ms | **337 req/s**, p95 135 ms | -3% |
| Top-ups (16 cash-in wallets) | 1,625 req/s, p95 30 ms | **1,567 req/s**, p95 32 ms | -4% |

Run-to-run spread after: spread 1,692-1,726; hot 333-337; top-ups 1,550-1,567. 0 errors, 0 rate-limited responses and
0 books-check violations in all nine runs. Raw files: `load-test/results/auth-*`.

**Reading it:** security cost about 3-5% of throughput. Be careful not to over-read that: the earlier numbers came from
a different session, and sessions on this machine differed by about 5% on their own, so the drop is about the size of
the noise. What the data supports is "no large cost", not "exactly 4%". It is plausible because the per-request work
added is small next to a database transaction: verifying an HS256 token is microseconds, the ownership check costs
one extra read of the source wallet before the locking query (a primary-key lookup, no lock), and the limiter is a few
arithmetic operations. (Logging in uses BCrypt,
which is slow on purpose, but that happens once per session, not per request, and is not in these numbers.) The p99
latency was not recorded in this round (the k6 export did not include it).

### After adding the outbox, publisher and Kafka consumer (M4)

Every transfer and top-up now also inserts an outbox row. A publisher sends the rows to Kafka and a consumer in the same
app writes `notification_log`. Method: same machine, same session, pool of 10, rate limit raised, fresh database per
scenario, three runs each, median shown. "Before" is the previous code (commit `ed506fa`) measured back to back with the
new code, so it is not the table above (that one came from another session; its spread figure is 3% higher).

| Scenario | Before (M3) | After (M4, everything on) | Change |
|---|---|---|---|
| Spread | 1,653 req/s, p95 31 ms, p99 40 ms | **1,145 req/s**, p95 48 ms, p99 72 ms | **-31%** |
| Hot wallet | 333 req/s, p95 138 ms, p99 158 ms | **261 req/s**, p95 174 ms, p99 200 ms | **-22%** |
| Top-ups | 1,471 req/s, p95 34 ms, p99 45 ms | **1,158 req/s**, p95 44 ms, p99 58 ms | **-21%** |

Run-to-run spread was small (spread after: 1,141-1,162; hot 257-267; top-ups 1,156-1,184). 0 errors, 0 rate-limited
responses and 0 books-check violations in all 18 runs. After every M4 run, `outbox_events` and `notification_log` each
had exactly one row per transaction (e.g. 241,895 of each after the third spread run), so nothing was lost or
duplicated under load.

**Where the cost comes from** (spread scenario only, three runs each, medians): the same code with the background jobs
switched off one at a time (`OUTBOX_PUBLISHER_ENABLED`, `NOTIFICATION_CONSUMER_ENABLED`):

| Configuration | req/s | p95 | vs. before |
|---|---|---|---|
| M3 code | 1,653 | 31 ms | - |
| Outbox insert only (publisher and consumer off) | 1,506 | 34 ms | -9% |
| + publisher sending to Kafka | 1,345 | 38 ms | -19% |
| + consumer writing `notification_log` | 1,145 | 48 ms | -31% |

**Reading it:**
- The insert in the money transaction itself is the cheapest part (-9%, one more row and index entry per transaction).
- The background jobs cost more than the insert. The likely reason is that they compete with the requests for the
  same 10 database connections and the same CPU (this machine also runs Kafka and k6). I did not isolate which of
  the two it is.
- The consumer's share is partly an artifact of running it inside the same app. As a separate service it would not
  take this app's connections, so the cost to the wallet service is closer to the -19% row.
- The publisher kept up: right after each run the unpublished backlog was 0. The consumer did not: it was still
  working through its backlog two minutes after the spread and top-up runs ended (about 25 s after the hot runs),
  which is lag, not loss.
- "Likely" and "did not isolate" are honest: this is one machine, with Docker Desktop, and the split was measured once.

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
- **M3** – done: Spring Security + JWT, wallet ownership, per-user rate limit on transfers, security mutation experiments ([docs](docs/DESIGN_DECISIONS.md#7-authentication-ownership-and-rate-limiting-m3))
- **M4** – done: transactional outbox, ordered at-least-once publisher, Kafka, idempotent consumer, mutation experiments ([docs](docs/DESIGN_DECISIONS.md#9-transactional-outbox-and-kafka-m4))
- **M5** – metrics dashboard, architecture diagram, load-test results
