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
