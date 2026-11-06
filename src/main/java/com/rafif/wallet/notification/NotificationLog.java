package com.rafif.wallet.notification;

import com.rafif.wallet.outbox.TransactionEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The "side effect" of consuming an event: one row in notification_log.
 * <p>
 * Idempotency lives in the database: {@code ON CONFLICT (event_id) DO NOTHING}. We do not "check, then insert"
 * because two deliveries of the same event could both pass the check; the unique constraint cannot be raced.
 */
@Component
class NotificationLog {

    private final JdbcTemplate jdbc;

    NotificationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this event was new, false if it had been processed before (a duplicate delivery) */
    boolean record(TransactionEvent event) {
        int inserted = jdbc.update("""
                INSERT INTO notification_log (event_id, transaction_id, type, from_wallet_id, to_wallet_id, amount)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(), event.transactionId(), event.type().name(),
                event.fromWalletId(), event.toWalletId(), event.amount());
        return inserted == 1;
    }
}
