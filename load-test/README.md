# Load test

A [k6](https://k6.io) script that drives the real HTTP API. k6 runs in Docker as a Compose service, so nothing needs
to be installed. The normal `docker compose up` does not start it.

## Run

```bash
# 1. start the app and database
docker compose up --build -d

# 2. run one scenario (spread | hot | topup); results are also written to load-test/results/
docker compose --profile loadtest run --rm -e SCENARIO=spread k6 run \
    --summary-export=/scripts/results/spread.json /scripts/transfer.js

# 3. check that the books still balance afterwards (the second result must be empty)
docker compose exec -T postgres psql -U wallet -d wallet < load-test/verify.sql
```

On Windows PowerShell, step 3 is: `Get-Content load-test/verify.sql | docker compose exec -T postgres psql -U wallet -d wallet`

## Scenarios

| `SCENARIO` | What it does | What it shows |
|---|---|---|
| `spread` | random transfers between 200 wallets, unique key each | normal throughput; little lock contention |
| `hot` | every transfer pays into or out of ONE wallet | the worst case: all requests queue on one row lock |
| `topup` | every request is a top-up | all top-ups lock the single cash-in wallet, so they run one after another |

About 5% of requests are retries of the previous request with the same key. They must be answered `200` from the
stored result (this exercises the idempotency path under load).

## Options (environment variables, add with `-e NAME=value`)

| Name | Default | Meaning |
|---|---|---|
| `VUS` | 32 | concurrent virtual users (each sends its next request as soon as the last one finishes) |
| `WARMUP_SECONDS` | 15 | warm-up phase, excluded from the numbers |
| `DURATION_SECONDS` | 60 | measured phase |
| `WALLETS` | 200 | wallets created and funded in `setup` |
| `REPLAY_PERCENT` | 5 | share of requests that retry the previous one |

## Reading the output

Use the lines tagged `{scenario:measured}` (the warm-up is excluded from those):

- `http_reqs{scenario:measured}`: total requests and **requests per second**
- `http_req_duration{scenario:measured}`: latency, with `p(95)`
- `wallet_unexpected{scenario:measured}`: must be `0` (no 5xx, no timeouts)
- `wallet_applied` / `wallet_replayed` / `wallet_refused`: how the requests ended

## What these numbers are, and are not

The test is a *closed* model: 32 users each send a request, wait for the answer, then send the next one. So the
latency shows how long a request takes when the server is saturated, and requests/second is the most this setup
sustains. They are measurements of one development machine with the app, the database and k6 all sharing it, a
default connection pool of 10, and Docker Desktop. They are not a production capacity claim. The README of the
project states the machine they were taken on.
