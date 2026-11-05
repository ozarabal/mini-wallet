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
