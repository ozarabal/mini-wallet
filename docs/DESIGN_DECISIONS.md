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

**Trade-off:** top-ups use the system cash-in wallet as the source, so every top-up locks the same row and top-ups
are serialized. Acceptable at this scale; the load test in M2 will measure it.

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
