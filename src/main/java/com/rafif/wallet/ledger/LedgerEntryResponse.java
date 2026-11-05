package com.rafif.wallet.ledger;

import java.time.Instant;
import java.util.UUID;

/** One line of a wallet's history: money in (CREDIT) or out (DEBIT), and the balance right after it. */
public record LedgerEntryResponse(
        UUID entryId,
        UUID transactionId,
        TransactionType type,
        Direction direction,
        long amount,
        long balanceAfter,
        Instant createdAt) {
}
