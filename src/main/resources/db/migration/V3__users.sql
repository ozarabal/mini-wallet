-- Users who can log in and own wallets (M3).
-- Usernames are case-insensitive: the application stores them in lowercase, so a plain UNIQUE is enough.
-- As with idempotency keys, the database constraint is what decides the winner when two people register the same
-- name at the same moment.
-- wallets.owner_id (text) will hold this id; there is deliberately no foreign key because the system cash-in wallets
-- are owned by 'SYSTEM', which is not a user.
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username)
);
