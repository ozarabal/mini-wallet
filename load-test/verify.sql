-- Run this AFTER a load test. It checks that the books still balance after thousands of concurrent requests.
-- First result: context (how much data was written). Second result: one row per violated rule.
-- An EMPTY second result means every check passed.

SELECT (SELECT count(*) FROM wallets)        AS wallets,
       (SELECT count(*) FROM transactions)   AS transactions,
       (SELECT count(*) FROM ledger_entries) AS ledger_entries;

-- 1. No wallet is below zero.
SELECT 'negative balance' AS violation, id::text AS detail
FROM wallets WHERE balance < 0

UNION ALL
-- 2. Money is conserved: every unit started in the system cash-in wallet (seeded by V1), so the total never changes.
SELECT 'money not conserved', sum(balance::numeric)::text
FROM wallets
HAVING sum(balance::numeric) <> 4611686018427387903

UNION ALL
-- 3. Each wallet's balance equals its ledger (the cash-in wallet also starts with its seed).
SELECT 'balance differs from ledger', w.id::text
FROM wallets w
LEFT JOIN (SELECT wallet_id,
                  sum(CASE direction WHEN 'CREDIT' THEN amount::numeric ELSE -amount::numeric END) AS net
           FROM ledger_entries GROUP BY wallet_id) l ON l.wallet_id = w.id
WHERE w.balance::numeric <>
      (CASE WHEN w.id = '00000000-0000-0000-0000-000000000001' THEN 4611686018427387903 ELSE 0 END)
      + COALESCE(l.net, 0)

UNION ALL
-- 4. Every transaction has debits equal to credits.
SELECT 'unbalanced transaction', transaction_id::text
FROM ledger_entries
GROUP BY transaction_id
HAVING sum(CASE direction WHEN 'DEBIT' THEN amount::numeric ELSE -amount::numeric END) <> 0

UNION ALL
-- 5. No transaction was left half-done.
SELECT 'transaction not completed', id::text
FROM transactions WHERE status <> 'COMPLETED';
