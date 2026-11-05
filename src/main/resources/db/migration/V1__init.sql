-- Wallets: balance is in minor units (IDR has no decimals) and can never be negative.
CREATE TABLE wallets (
    id         UUID        PRIMARY KEY,
    owner_id   VARCHAR(64) NOT NULL,
    currency   VARCHAR(3)  NOT NULL DEFAULT 'IDR',
    balance    BIGINT      NOT NULL CHECK (balance >= 0),
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wallets_owner ON wallets (owner_id);

-- One row per business operation (top-up or transfer).
-- The unique constraint is what guarantees idempotency under concurrency.
CREATE TABLE transactions (
    id              UUID        PRIMARY KEY,
    type            VARCHAR(16) NOT NULL CHECK (type IN ('TOPUP', 'TRANSFER')),
    status          VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    owner_id        VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transactions_owner_key UNIQUE (owner_id, idempotency_key)
);

-- Double-entry ledger: every transaction has one DEBIT and one CREDIT of equal amount.
CREATE TABLE ledger_entries (
    id             UUID        PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions (id),
    wallet_id      UUID        NOT NULL REFERENCES wallets (id),
    direction      VARCHAR(8)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    balance_after  BIGINT      NOT NULL CHECK (balance_after >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_wallet_created ON ledger_entries (wallet_id, created_at DESC);
CREATE INDEX idx_ledger_transaction ON ledger_entries (transaction_id);

-- System "cash-in" wallet: the source of every top-up.
-- Seeded with a huge balance so CHECK (balance >= 0) stays uniform for all wallets.
INSERT INTO wallets (id, owner_id, currency, balance)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'IDR', 4611686018427387903);
