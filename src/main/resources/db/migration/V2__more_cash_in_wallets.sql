-- Every top-up locks its source cash-in wallet. With a single cash-in wallet, all top-ups took turns on that one row
-- (about 350 per second in the M2 load test). Sixteen cash-in wallets let up to sixteen top-ups run side by side.
--
-- V1 already created cash-in wallet number 1 (id ...0001, with a huge balance). It is left untouched; this adds
-- numbers 2 to 16 with ids ...0002 to ...0010 (hex), owned by SYSTEM like the first one.
-- Each starts with 10^15 (a quadrillion rupiah): far more than any test or demo will ever top up, and small enough
-- that the sum of all balances still fits in a bigint.
--
-- The ids here must match SystemWallets.CASH_IN_IDS in the code (SchemaIT checks that they do).
INSERT INTO wallets (id, owner_id, currency, balance)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid, 'SYSTEM', 'IDR', 1000000000000000
FROM generate_series(2, 16) AS n;
