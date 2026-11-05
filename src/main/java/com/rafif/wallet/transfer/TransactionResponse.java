package com.rafif.wallet.transfer;

import com.rafif.wallet.ledger.Transaction;
import java.time.Instant;
import java.util.UUID;

/** Result of a top-up or transfer. For a top-up, fromWalletId is the system cash-in wallet. */
public record TransactionResponse(
        UUID transactionId,
        String type,
        String status,
        UUID fromWalletId,
        UUID toWalletId,
        long amount,
        Instant createdAt) {

    static TransactionResponse of(Transaction tx, UUID fromWalletId, UUID toWalletId, long amount) {
        return new TransactionResponse(
                tx.getId(), tx.getType().name(), tx.getStatus().name(),
                fromWalletId, toWalletId, amount, tx.getCreatedAt());
    }
}
