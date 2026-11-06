-- Transactional outbox: one row per completed business operation, written in the SAME database transaction as the
-- ledger entries. A separate publisher (added later) reads unpublished rows and sends them to Kafka, so an event
-- exists if and only if the money really moved.
CREATE TABLE outbox_events (
    -- Publishing order. An identity column is handed out while the transaction holds the wallet locks, so for any
    -- one wallet a later transfer always gets a higher id.
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- What consumers use to recognise a duplicate delivery.
    event_id      UUID         NOT NULL,
    aggregate_id  UUID         NOT NULL REFERENCES transactions (id),
    type          VARCHAR(32)  NOT NULL CHECK (type IN ('TRANSFER_COMPLETED', 'TOPUP_COMPLETED')),
    -- Kafka message key: events with the same key stay in order.
    partition_key VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    -- One operation, one event: a bug that wrote it twice would fail loudly instead of announcing money twice.
    CONSTRAINT uq_outbox_aggregate UNIQUE (aggregate_id)
);

-- The publisher only ever asks for "the oldest unpublished rows". A partial index keeps that cheap
-- however many published rows pile up.
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
