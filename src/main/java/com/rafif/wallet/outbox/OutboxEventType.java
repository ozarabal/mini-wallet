package com.rafif.wallet.outbox;

/** What happened. The names are also stored in the database (see the CHECK constraint in V4). */
public enum OutboxEventType {
    TRANSFER_COMPLETED,
    TOPUP_COMPLETED
}
