-- What the notification consumer has processed. It stands in for "send an email / push notification".
-- The UNIQUE constraint on event_id is what makes the consumer idempotent: Kafka delivers at least once, so the same
-- event can arrive twice, and the second insert is silently ignored (INSERT ... ON CONFLICT DO NOTHING).
CREATE TABLE notification_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID        NOT NULL,
    transaction_id  UUID        NOT NULL,
    type            TEXT        NOT NULL,
    from_wallet_id  UUID        NOT NULL,
    to_wallet_id    UUID        NOT NULL,
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event UNIQUE (event_id)
);
