package com.rafif.wallet.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The message other systems receive. It is a contract with them, so it only contains what they need.
 * It deliberately has NO user or owner ids: once a message is in Kafka it is copied to many places and cannot be
 * taken back, so personal data stays out.
 *
 * @param eventId       unique per event; consumers use it to ignore duplicate deliveries
 * @param transactionId the business transaction this announces
 * @param fromWalletId  money left this wallet (for a top-up: a system cash-in wallet)
 * @param toWalletId    money arrived in this wallet
 */
public record TransactionEvent(
        UUID eventId,
        UUID transactionId,
        OutboxEventType type,
        UUID fromWalletId,
        UUID toWalletId,
        long amount,
        Instant occurredAt) {
}
