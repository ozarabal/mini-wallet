package com.rafif.wallet.transfer;

import com.rafif.wallet.ledger.Direction;
import com.rafif.wallet.ledger.LedgerEntry;
import com.rafif.wallet.ledger.Transaction;
import java.time.Instant;
import java.util.List;
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

    /**
     * Rebuilds the response of an already-applied request from what is stored, so a retry gets the
     * same answer without us having to keep a copy of the original response body.
     */
    static TransactionResponse fromStored(Transaction tx, List<LedgerEntry> entries) {
        LedgerEntry debit = entries.stream().filter(e -> e.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
        return of(tx, debit.getWalletId(), credit.getWalletId(), debit.getAmount());
    }
}
