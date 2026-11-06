# Load test

A [k6](https://k6.io) script that drives the real HTTP API. k6 runs in Docker as a Compose service, so nothing needs
to be installed. The normal `docker compose up` does not start it.

## Run

```bash
# 1. start the app and database. The per-user rate limit (default: burst 20, 10/s) would throttle a load test,
#    because the whole point is to send as many requests as possible, so raise it for the test.
RATE_LIMIT_BURST=1000000 RATE_LIMIT_REFILL=1000000 docker compose up --build -d

# 2. run one scenario (spread | hot | topup); results are also written to load-test/results/
docker compose --profile loadtest run --rm -e SCENARIO=spread k6 run \
    --summary-export=/scripts/results/spread.json /scripts/transfer.js

# 3. check that the books still balance afterwards (the second result must be empty)
docker compose exec -T postgres psql -U wallet -d wallet < load-test/verify.sql
```

**Keep the rate-limit variables set for every `docker compose` command in the same shell** (including the `run k6`
one). Compose recreates the app container whenever its settings differ, so a command without them silently puts the
default limit back. If that happens the test shows `wallet_rate_limited` > 0 and the run fails its threshold: the
numbers from such a run are not valid.

On Windows (Git Bash), prefix the k6 command with `MSYS_NO_PATHCONV=1`, otherwise `/scripts/...` is rewritten into a
Windows path. In PowerShell set the variables with `$env:RATE_LIMIT_BURST='1000000'` and step 3 is: `Get-Content load-test/verify.sql | docker compose exec -T postgres psql -U wallet -d wallet`

## Users and tokens

The API requires a login and only lets you spend from wallets you own, so `setup` registers `USERS` users (default
20), logs each in, and gives each user `WALLETS / USERS` wallets (default 10). Virtual user N acts as user
`N mod USERS` and sends that user's token with every request. Tokens last 15 minutes, longer than a run. Each run
creates new users (`load-<runId>-<n>`), so repeat runs on the same database do not collide.

## Scenarios

| `SCENARIO` | What it does | What it shows |
|---|---|---|
| `spread` | each user sends transfers from one of their own wallets to a random wallet of anyone, unique key each | normal throughput; little lock contention |
| `hot` | every transfer pays into or out of ONE wallet (owned by one extra "merchant" user: everybody pays it, only the merchant pays out) | the worst case: all requests queue on one row lock |
| `topup` | every request is a top-up | each top-up locks one of the 16 system cash-in wallets (chosen by its key) and the target wallet. With a single cash-in wallet all top-ups took turns on it (~330 req/s); see DESIGN_DECISIONS.md section 4 |

About 5% of requests are retries of the previous request with the same key. They must be answered `200` from the
stored result (this exercises the idempotency path under load).

## Options (environment variables, add with `-e NAME=value`)

| Name | Default | Meaning |
|---|---|---|
| `VUS` | 32 | concurrent virtual users (each sends its next request as soon as the last one finishes) |
| `WARMUP_SECONDS` | 15 | warm-up phase, excluded from the numbers |
| `DURATION_SECONDS` | 60 | measured phase |
| `WALLETS` | 200 | wallets created and funded in `setup`, shared out evenly between the users |
| `USERS` | 20 | users registered in `setup` |
| `REPLAY_PERCENT` | 5 | share of requests that retry the previous one |

## Changing the connection pool size

The app's database pool (how many requests can use the database at once) defaults to 10. To try another size,
set `DB_POOL_SIZE` when starting the app, and use a fresh database for comparable runs:

```bash
docker compose down -v && DB_POOL_SIZE=20 docker compose up --build -d
```

## Reading the output

Use the lines tagged `{scenario:measured}` (the warm-up is excluded from those):

- `http_reqs{scenario:measured}`: total requests and **requests per second**
- `http_req_duration{scenario:measured}`: latency, with `p(95)`
- `wallet_unexpected{scenario:measured}`: must be `0` (no 5xx, no timeouts)
- `wallet_rate_limited{scenario:measured}`: must be `0`; anything else means the rate limit throttled the test
- `wallet_applied` / `wallet_replayed` / `wallet_refused`: how the requests ended

## What these numbers are, and are not

The test is a *closed* model: 32 users each send a request, wait for the answer, then send the next one. So the
latency shows how long a request takes when the server is saturated, and requests/second is the most this setup
sustains. They are measurements of one development machine with the app, the database and k6 all sharing it, a
default connection pool of 10, and Docker Desktop. They are not a production capacity claim. The README of the
project states the machine they were taken on.

## Switching the background jobs off (to measure what they cost)

Since M4 the app also runs an outbox publisher and a Kafka consumer. To see what each costs, start the stack with
either switched off (set the variables for every `docker compose` command, as with the rate limit):

| Variable | Default | Effect when `false` |
|---|---|---|
| `OUTBOX_PUBLISHER_ENABLED` | true | events stay in the outbox table; nothing is sent to Kafka |
| `NOTIFICATION_CONSUMER_ENABLED` | true | nothing reads Kafka; `notification_log` stays empty |

With both off, only the outbox insert in the money transaction remains. Results: README, section "Performance".
After a run with the jobs ON the publisher and consumer may still be working: wait until
`SELECT count(*) FROM outbox_events WHERE published_at IS NULL` is 0 and `notification_log` has as many rows as
`outbox_events` before starting the next run, or the leftovers distort it.
