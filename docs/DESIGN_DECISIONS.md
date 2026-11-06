# Design decisions

## 1. Locking strategy for transfers

**Decision:** pessimistic row locks. A transfer runs in one DB transaction and locks both wallets with a single
`SELECT ... WHERE id IN (from, to) ORDER BY id FOR UPDATE`.

**Why it is safe**
- The balance check and the balance update happen while we hold the row lock, so two transfers out of the same
  wallet cannot both see the old balance (no lost update, no overdraw).
- Deadlock: a deadlock needs a cycle (T1 holds A and waits for B, T2 holds B and waits for A). Because every
  transaction takes its locks in ascending id order, T2 would have to take A first too, so it waits for T1 and no
  cycle can form. The order is decided by the database (`ORDER BY id`), so it is identical for every transaction.
- The default READ COMMITTED isolation is enough: correctness comes from the row locks, not from the isolation level.

**Alternatives considered**
| Option | Why not |
|---|---|
| Optimistic locking (`@Version` + retry) | Under contention on a hot wallet most attempts fail and must retry; more code, worse latency. `version` is still kept as a safety net. |
| SERIALIZABLE isolation | Correct, but needs retry handling for serialization failures and is harder to reason about and explain. |
| `UPDATE wallets SET balance = balance - ? WHERE id = ? AND balance >= ?` | Atomic and lock-light, but we also need to read both wallets, write ledger entries with `balance_after`, and keep one clear code path. |
| Lock wallets one by one in a Java-sorted loop | Also deadlock-free, but a single ordered query is shorter and easier to explain. |

**Trade-off:** a top-up locks its source cash-in wallet too. With a single cash-in wallet that made every top-up in
the system take turns on one row; the M2 load test measured it (about 330 req/s against about 1,800 for transfers
between different wallets). That one is a design choice, not a real constraint, and section 4 removes it.

## 2. Idempotency: database unique constraint, not an application check

**Decision:** every top-up/transfer needs an `Idempotency-Key`. The pair `(owner_id, idempotency_key)` is `UNIQUE`
in the `transactions` table, and the request is stored with a SHA-256 `request_hash` of its canonical form.

**Flow** (`IdempotencyService`, which is deliberately *not* transactional)
1. Look up `(owner, key)`. Found + same hash -> return the stored result (HTTP 200, same transaction id, nothing
   written). Found + different hash -> 422 `IDEMPOTENCY_KEY_REUSED`.
2. Not found -> run the operation in ONE DB transaction (lock wallets, insert the `transactions` row, move money,
   write ledger entries, commit).
3. If a concurrent request with the same key commits first, our insert violates the unique constraint. We catch
   that **outside** the failed transaction and run step 1 again in a fresh transaction, which now finds the winner.

**Why a unique constraint**
- "Check if the key exists, then insert" is a race: two requests can both see "not found". Only the database can
  decide atomically who wins. The lookup in step 1 is just a fast path for ordinary retries.
- The winner's row is only visible once its whole transaction (money + ledger) has committed, so a retry can never
  observe a half-applied request.
- The replayed response is rebuilt from the stored transaction + ledger entries, so no response body is stored.

**Why the catch is outside the transaction:** PostgreSQL marks a transaction as aborted after any error; it cannot
run another query. So the retry must start a new transaction.

**Choices and trade-offs**
- *Scope is the wallet owner* (source wallet for transfers, target for top-ups). In M3 this becomes the JWT user.
- *The request type is part of the hash*, so one key cannot mean a transfer once and a top-up another time.
- *A failed request does not consume the key* (e.g. `INSUFFICIENT_FUNDS`): everything rolls back, including the
  `transactions` row, so a retry after topping up can succeed. Alternative: store failures too and replay them.
  That needs a second write path that survives the rollback; not worth the complexity here.
- *Keys never expire* in this version. A real system would purge old keys (e.g. after 24h) with a scheduled job.
- Alternatives rejected: an in-memory/Redis lock (loses the guarantee on restart or with several instances);
  `SERIALIZABLE` isolation (needs retry loops and does not by itself give replay semantics).

## 3. How we know the tests can actually fail (mutation experiments)

A test that passes proves little unless it also fails when the code is wrong. Each experiment below breaks one
safeguard on purpose, runs the whole suite from a clean build (`./mvnw clean verify`), and records which tests
notice. Every change was reverted afterwards; the final clean build on the original code is green (50 tests).

| # | Deliberate breakage | Caught by | What the failure looked like |
|---|---|---|---|
| 1a | Remove the row lock (plain read); `@Version` kept | 6 concurrency tests | `ObjectOptimisticLockingFailureException`: the version column turns would-be lost updates into loud failures instead of silent corruption. This is why `@Version` stays as a safety net. |
| 1b | Remove the row lock **and** `@Version` | overdraw tests, deadlock tests | 100 of 100 concurrent transfers were applied instead of 30 (every thread read the same stale balance), and the opposite-direction test ended at 99,980 instead of 100,000. Money created and destroyed. |
| 2 | Lock the two wallets one at a time in request order instead of sorted order | opposite-direction test, 3-wallet ring test, random-operations test | PostgreSQL aborted transactions with `deadlock detected` (`CannotAcquireLockException`). |
| 3a | Do not treat a lost idempotency race as a replay (rethrow instead) | both same-key concurrency tests, random-operations test | The losing requests failed instead of returning the winner's result. Money stayed correct, because the unique constraint still blocked the duplicate. |
| 3b | Drop the database unique constraint (application logic untouched) | both same-key concurrency tests, random-operations test | Duplicate `transactions` rows for one key (`Query did not return a unique result: 2 results were returned`); an earlier run saw 17 of 20 identical requests applied. The application-level check alone does not stop a race: the constraint is what makes "exactly once" true. |
| 4 | `MoneyMover` skips the CREDIT ledger entry | 19 tests in 7 classes, including `LedgerInvariantsIT` | A balance changed with no matching ledger line. The invariant checker reports it; `TransferApiIT` and the history tests fail on the missing entry. |

**Why experiment 2 locks one wallet at a time instead of just deleting `ORDER BY`:** with only two rows in one
`IN (...)` query, PostgreSQL tends to lock them in the same physical order whatever the query says, so removing the
`ORDER BY` alone might still be deadlock-free by accident and would prove nothing. Locking separately in request
order is the real failure that ordered locking prevents.

**Caveats found while doing this (kept for honesty)**
- When one test fails, a later test can fail too without being related. A failing test leaves worker threads
  finishing their last operations while the next test measures the shared cash-in wallet, so `TopUpIT
  .concurrentTopUpsAreAllApplied` showed "money not conserved" in several experiments. Run alone under the same
  breakage it passes (checked for experiments 2 and 3b). This only affects runs that are already failing.
- In 1b, `LedgerInvariantIT` and `WalletControllerIT` also failed, but only because removing `@Version` stops Spring
  Data from creating wallets at all (a side effect of the mutation, not evidence about locking). They are not
  counted as catchers of 1b.
- In experiment 4 the random-operations test failed *incidentally* (it crashed while rebuilding a replayed response
  with no CREDIT entry) before reaching the invariant checker. The checker itself is proven by `LedgerInvariantsIT`.
- Stale build output can mislead: restoring a file with an old timestamp made Maven keep the mutated migration in
  `target/classes`, which produced 4 false failures on correct code. All results above are from `clean` builds.

**To repeat an experiment:** make the one-line change, run `./mvnw clean verify`, expect the failures listed, then
revert the change and run `./mvnw clean verify` again.

## 4. Spreading top-ups over 16 cash-in wallets

**Problem (found by the M2 load test):** top-ups ran at ~350 req/s while transfers between different wallets ran at
~1,800. A top-up is a transfer from the system cash-in wallet, and a transfer locks both of its wallets, so with ONE
cash-in wallet every top-up in the whole system took turns on a single row. The hot-wallet case is similar but is a
real limit (many people paying the same wallet). This one was a design choice.

**Decision:** 16 cash-in wallets (the one from V1 plus 15 added by V2, all owned by `SYSTEM`). A top-up picks its
cash-in wallet as `hashCode(idempotencyKey) mod 16` (`SystemWallets.cashInFor`), then locks that wallet and the target
wallet exactly as before.

**Total money check:** the "total money is constant" checks (`LedgerInvariants`, `load-test/verify.sql`) sum over ALL
16 cash-in wallets, because they are part of the closed system; checking only one would report money as lost.

**Why it is still safe**
- Lock order is unchanged: a top-up locks exactly two wallets through the same ordered query, so a global ascending
  order still holds and no deadlock cycle can form, even with top-ups and transfers mixed (tested: 400 mixed
  operations, both directions).
- The same key always maps to the same cash-in wallet (`String.hashCode` is fixed by the Java spec, so it is stable
  across restarts and instances). A replay does not need the mapping at all: it reads the wallet that was really
  debited from the stored ledger entry, so it returns the identical response.
- `V1` was not edited: Flyway checksums applied migrations, so changing it would break every existing database. The
  15 new wallets come from `V2`. Each starts with 10^15 (the first keeps its 4.6x10^18) so the sum of all balances
  still fits in a bigint.

**Alternatives considered**
| Option | Why not |
|---|---|
| Pick by the target wallet id | Top-ups to one popular wallet would still pile onto one cash-in wallet. (They queue on the target wallet anyway, but a spread by key never concentrates load.) |
| Pick a random wallet per request | Not repeatable: two racing requests with the same key could lock different cash-in wallets. Still correct (the unique constraint decides), but harder to reason about. |
| Create money with no source wallet | Breaks double-entry: the ledger would no longer balance per transaction. |
| Lock the cash-in wallet last (late `UPDATE`) | Shorter hold time, but it is still one row, and it breaks the single global lock order that makes deadlocks impossible. |
| More than 16 wallets | Not needed to prove the point; see the open question below. |

**Result** (32 users, 60 s measured after 15 s warm-up, median of 3 runs, fresh database, same machine and session):

| | Requests/sec | p95 | p99 |
|---|---|---|---|
| Before: 1 cash-in wallet | 330 (326-335) | 139 ms | 161 ms |
| After: 16 cash-in wallets | **1,625** (1,612-1,632) | **30 ms** | 39 ms |

That is 4.9x. The 16 wallets were used evenly: each paid for between 6.2% and 6.3% of the top-ups (a perfect spread
is 6.25%). A first measurement earlier the same day gave 349 req/s for "before"; the 5% difference is the drift between
sessions, which is why "before" was measured again right before "after".

**Mutation check:** making `cashInFor` always return the first wallet (the old behaviour) breaks no correctness test,
because the system is still correct, only slow again. It is caught by exactly the tests that check the spread:
`SystemWalletsTest` (two tests) and `TopUpIT.topUpsAreSpreadOverSeveralCashInWallets`. A performance regression slips
past ordinary correctness tests, which is why those tests exist. (Maven stops after failing unit tests, so the
integration tests had to be run on their own to see this.)

**Limits and open questions**
- A cash-in wallet that runs out of money would make a top-up fail with `INSUFFICIENT_FUNDS`, which would be a
  confusing answer to a top-up. At 10^15 per wallet this cannot happen at this scale, and it is not handled.
- The `fromWalletId` of a top-up response is now one of 16 ids, not a constant.
- With a pool of 32 connections top-ups reached 2,705 req/s against 3,510 for spread, with a higher p99. Likely
  explanation: 32 concurrent top-ups over 16 wallets sometimes pick the same one and wait. Not tested; more cash-in
  wallets (32 or 64) would test it.

## 5. Database connection pool size

While checking whether top-ups could reach the spread numbers, the pool became the next question: 32 users shared
HikariCP's default of 10 connections, so up to 22 requests at a time were waiting for a connection. The pool size is
now written in `application.yml` and set with `DB_POOL_SIZE` (default 10, unchanged). The app reports the size it
runs with (`hikaricp_connections_max` on `/actuator/prometheus`), which was checked before every run.

| Pool size | Spread req/s (p95) | Top-ups req/s (p95) |
|---|---|---|
| 10 (default) | 1,795 (28 ms) | 1,625 (30 ms) |
| 20 | 2,834 (18 ms) | 2,372 (23 ms) |
| 32 | 3,510 (16 ms) | 2,705 (26 ms) |

Median of 3 runs each, fresh database each time. All runs had 0 unexpected responses and 0 books-check violations.

**Reading it:** on this machine, with 32 users, the default pool limited the normal case; 32 connections nearly
doubled throughput (+96%). I did not change the default, because the best value is not a property of the code: it
depends on the database's CPU cores, its `max_connections` (100 by default; total connections from all app instances
must stay under it), and how long each request holds a connection. Here the app and the database shared one CPU, so
more connections also means more competition for it. Pools larger than the number of users (32) cannot help in this
test, so larger values were not tried. The hot-wallet scenario was not re-run with bigger pools; I expect no gain
because its limit is the single row lock, but that is an expectation, not a measurement.

## 6. Security mutation experiments (ownership, tokens, rate limit, registration)

Same method as section 3: break one safeguard, run `./mvnw clean verify`, record which tests fail, revert. Each
experiment changed one line. After all of them, a clean build on the original code is green (126 tests).

| # | Deliberate breakage | Caught by |
|---|---|---|
| 5a | Delete the ownership check in `TransferService` (spend from any wallet) | 5 `OwnershipIT` tests: spending someone else's money, taking money out of a cash-in wallet, replaying someone else's idempotency key, the 100-thief race, 404-vs-403 order |
| 5b | Delete the ownership check in `TopUpService` | `OwnershipIT.anotherUserCannotTopUpMyWalletAndNothingIsWritten` (the only catcher) |
| 5c | Delete the ownership check in `WalletService.get` (read a balance) | 3 `OwnershipIT` tests (reading a foreign wallet, reading a cash-in wallet, created wallets belong to their creator) |
| 5d | Delete the ownership check in `LedgerQueryService.history` | 2 `OwnershipIT` tests (foreign history, cash-in wallet) |
| 6 | Token decoder accepts expired tokens (clock skew of 10 years) | `SecurityIT.anExpiredTokenIsRejected` (the only catcher) |
| 7a | Rate limiter always allows | 9 `TokenBucketRateLimiterTest` and 5 `RateLimitIT` tests |
| 7b | Rate limiter uses one shared bucket for every user | 2 unit tests and 5 `RateLimitIT` tests, plus 2 `OwnershipIT` tests incidentally (users ran out of the shared budget) |
| 8 | Registration does not turn a unique-constraint violation into `USERNAME_TAKEN` | `AuthIT.concurrentRegistrationsOfTheSameUsernameCreateExactlyOneUser` (the only catcher) |

**What this tells us**
- Every ownership check is covered separately: removing any one of the four fails at least one test. The check in
  each place matters because a missing one on just one endpoint would still leak or lose money.
- Experiments 5b, 6 and 8 each have exactly one catching test. Those are the thinnest spots; if one of those tests
  is ever deleted or weakened, the protection silently disappears.
- 8 shows the early `existsByUsername` check is not enough: only the database constraint, plus handling its
  violation, makes concurrent registration of one name safe.

**Caveats**
- For 7a and 7b the first run stopped after the unit tests failed, so the integration tests never ran. The numbers
  above are from re-runs with `-Dmaven.test.failure.ignore=true`, so both layers were measured.
- Not tried: moving the ownership check after the idempotency lookup (to show the key-leak test catches it), and
  wrong-algorithm or wrong-issuer decoders. `SecurityIT` has tests for those, but I did not mutate the code to prove
  they fail.

## 7. Authentication, ownership and rate limiting (M3)

### Tokens: signed JWT checked by Spring's resource server

| Choice | Why | Alternatives considered |
|---|---|---|
| Self-contained JWT, HS256, 15-minute lifetime | Every request is verified from the signature alone: no database read per request. One app both issues and checks tokens, so one shared secret is enough. | **Server-side sessions**: needs shared session storage, and CSRF protection because of cookies. **RS256/ES256 (key pair)**: worth it when *other* services verify tokens without being able to mint them; nobody else verifies here, so it would only add key handling. |
| Only Spring's own starters (`security`, `oauth2-resource-server`); no third-party JWT or rate-limit library | Fewer dependencies to explain; the resource server already does signature, expiry and issuer checks well. | `jjwt`, `auth0 java-jwt`: extra dependency for what the starter does. Bucket4j for rate limiting: see below. |
| Decoder **pinned to HS256**, issuer checked, 5 s clock skew, subject must be a UUID | Closes the classic holes: `alg=none` and algorithm confusion (HS512, RS256) are rejected; a validly signed token with a junk subject is rejected instead of crashing later. Spring's default 60 s skew was reduced because one app issues and checks. | Default decoder settings. |
| Secret from `JWT_SECRET`, at least 32 bytes, **no default**: the app will not start without it | A forgotten secret must not silently become a well-known one. `docker-compose.yml` has a clearly named dev-only value so `docker compose up` works. | A default in `application.yml` (a leaked default means anyone can mint tokens). |
| BCrypt, passwords of 8-72 bytes, usernames lowercased | BCrypt only reads 72 bytes, so longer input would be silently truncated; refusing it is honest. Lowercasing makes `Alice` and `alice` the same account. | Argon2 (better, but not in the listed dependencies; BCrypt ships with Spring Security). |
| Unknown user at login runs a dummy BCrypt compare and returns the same 401 as a wrong password | Without it, the response *time* would tell an attacker which usernames exist. | Returning a different error for an unknown user (leaks which names are registered). |

### Who may do what

- The wallet owner comes **only from the token** (`POST /wallets` has no body). A request cannot name an owner.
- Owner-only: read balance, read history, top up, spend. Anyone may pay *into* a wallet (that is how people receive
  money). Reading is owner-only too, because a balance and a history are private financial data.
- **403 for someone else's wallet, 404 for an unknown one.** This lets a caller tell "exists but not mine" from
  "does not exist". Wallet ids are random UUIDs, so guessing one is not practical, and the clearer error is more
  useful to honest clients. If ids were guessable (sequential numbers) this would have to become 404 for both.
  The self-transfer check (400) runs *before* the ownership check so it cannot be used to probe wallets either way.
- **The ownership check runs before the idempotency lookup.** Otherwise anyone who learned someone's key could
  replay it and read back the stored result. Keys are also scoped by the owner id (the existing unique constraint is
  `(owner_id, idempotency_key)`), so two users using the same key do not collide. Both are tested.
- The 16 system cash-in wallets belong to the owner `SYSTEM`, which no user id equals, so no user can spend from or
  read them. Before this check any user could "transfer" money out of a cash-in wallet to themselves: free money.
  `OwnershipIT.nobodyCanTakeMoneyOutOfACashInWallet` covers it.
- The ownership check is a read before the locking query (one extra primary-key lookup). The alternative, checking
  after taking the lock, would hold row locks while refusing strangers, which a flood of unauthorised requests
  could use to slow down the real owner. Ownership of a wallet never changes, so the unlocked read cannot go stale.

### Rate limiting: hand-written token bucket, per user, in memory

| Choice | Why | Alternatives considered |
|---|---|---|
| Token bucket (burst 20, refill 10/s), per user, `POST /transfers` only | Allows short bursts but caps the sustained rate; simple enough to explain and test line by line. Top-ups and reads are not limited here (they could be; transfers are the main target). | **Fixed window counter**: lets 2x the limit through around a window boundary. **Sliding window log**: exact, but stores a timestamp per request. |
| Written by hand (about 100 lines) with an injectable `Clock` | No new dependency; unit tests run with a fake clock and no sleeping. | **Bucket4j**: well tested and supports Redis, but it is a dependency to justify, and the goal is code I can explain. |
| In the security filter chain, after token verification | The limit is per user, which is only known once the token is verified. Unauthenticated requests are answered 401 and never use anyone's budget. | A servlet filter before security (cannot know the user); limiting by IP (shared by many users behind one address). |
| Every request counts, including replays and requests that later fail validation | The limiter protects server capacity; a replay still costs a database lookup. | Counting only applied transfers (would let an attacker hammer the replay path for free). |
| Idle buckets removed by a sweep every 60 s | Memory stays proportional to *active* users. A full bucket equals a brand-new one, so dropping it changes nothing. | Never removing (leak); a background thread (needless complexity). |

### Known gaps (deliberately out of scope, listed so nobody assumes otherwise)

- **No refresh tokens and no revocation.** A stolen token works for up to 15 minutes; logging out cannot invalidate
  it. A token also stays valid if its user were deleted (there is no delete-user feature). Fixing this needs a
  token store or short tokens plus refresh tokens.
- **No brute-force protection on login.** BCrypt makes each guess slow, but nothing limits guesses per account or per
  address. The rate limiter could be applied to `/auth/login` keyed by username/IP.
- **The rate limiter is per application instance.** With N instances the real limit is N times higher, and a restart
  resets every bucket. A shared limiter (Redis) is the fix once there is more than one instance.
- **`/actuator/prometheus` and `/actuator/health` are public**, as is Swagger. Fine for a local demo; to be locked
  down or moved to a separate management port in M5.
- **HS256 means the secret both signs and verifies**: anyone who can verify tokens could also forge them. That is
  acceptable while only this app verifies tokens.
- **Passwords have a length rule only**, no breached-password check or complexity policy.
- **Old data**: wallets created before M3 have free-text owners (such as "alice") that match no user id, so they are
  unreachable. There was no production data; a clean database (`docker compose down -v`) is the migration.

## 8. Outbox and Kafka mutation experiments (M4)

Same method as sections 3 and 6: break one safeguard, run the relevant integration tests with
`-Dmaven.test.failure.ignore=true`, record which tests fail, revert (`git checkout`). Each experiment changed one line.

| # | Deliberate breakage | Caught by |
|---|---|---|
| 9 | `OutboxWriter` runs in its own transaction (`REQUIRES_NEW` instead of `MANDATORY`), so the event is no longer written together with the money movement | `OutboxIT.theEventRollsBackWithTheTransactionItIsWrittenIn` and `OutboxIT.theWriterRefusesToRunWithoutATransaction` directly. Also about 60 other tests, see the note below |
| 10 | Advisory lock never taken (`if (false)` instead of `if (!tryAcquirePublisherLock())`) | `OutboxPublisherIT.anotherPublisherAlreadyRunningMeansThisOneDoesNothing` and `manyPublishersAtOnceStillSendEachEventOnceAndInOrder` (2 tests, the only catchers) |
| 11 | Event marked published BEFORE it is sent | `OutboxPublisherIT.aFailureStopsTheRunKeepsEarlierEventsMarkedAndRetriesInOrder` (the only catcher): the failed event stayed marked and was never retried, i.e. lost |
| 12 | Consumer without `ON CONFLICT DO NOTHING` (plain insert) | `NotificationConsumerIT.theSameEventDeliveredTwiceIsProcessedOnce`. It fails by timeout: the duplicate insert violates the unique constraint, the message is retried forever, and the event behind it is never processed |
| 13 | Consumer error handler gives up after 2 retries instead of retrying without limit (the shape of Spring's default) | `NotificationConsumerIT.whenTheDatabaseFailsTheMessageIsRetriedAndNotLost`: the event was skipped during the outage and never arrived |
| 14 | `KafkaEventSender` returns without waiting for the broker's acknowledgement | **Survived the first run: no test failed.** I then added `KafkaEventSenderTest` (3 tests with a fake `KafkaTemplate` that controls when the acknowledgement arrives). With the test in place, the mutant fails all 3 and the original passes all 3 |

**What this tells us**
- Experiments 10, 11, 12, 13 and 14 each have a thin safety net (one or two tests). They are the ones to protect if
  tests are ever reorganised.
- Experiment 14 is the useful surprise. The real-broker tests in `KafkaEventSenderIT` would not notice a sender that
  stops waiting, because a healthy broker acknowledges anyway, and an unreachable broker makes `send()` throw before
  any waiting starts. Only a fake that delays or withholds the acknowledgement can show the difference.
- Experiment 9: the 60-odd extra failures are a side effect, not extra coverage. My explanation (not verified in
  detail) is that the event row has a foreign key to the transaction row, which is still uncommitted when the writer
  runs in its own transaction, so the insert waits on that row lock until the 30 s test timeouts. A wrong
  transaction boundary is therefore loud rather than silent, which is good, but the two tests named above are the
  ones that explain *why*.

**Caveats**
- Experiment 13 is an approximation of "the default handler" (two quick retries, then skip), not a run with Spring's
  actual default settings.
- Not tried: removing the unique constraint `uq_outbox_aggregate`, or changing the Kafka key. Both have direct
  tests (`OutboxIT`, `KafkaEventSenderIT`), but I did not mutate the code to prove they fail.
- Each experiment ran only the integration test classes that concern it (all non-Kafka ones for #9), not the whole
  suite. A final clean `./mvnw clean verify` on the original code is green (see the commit report).
