package com.rafif.wallet.transfer;

import com.rafif.wallet.ledger.Direction;
import com.rafif.wallet.ledger.LedgerEntry;
import com.rafif.wallet.ledger.LedgerEntryRepository;
import com.rafif.wallet.wallet.Wallet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place that changes balances. It updates both wallets and writes the matching DEBIT and CREDIT
 * ledger entries, so a balance change without a ledger entry is impossible.
 * MANDATORY means it refuses to run outside a transaction: the caller must already hold the wallet locks.
 */
@Component
class MoneyMover {

    private final LedgerEntryRepository entries;

    MoneyMover(LedgerEntryRepository entries) {
        this.entries = entries;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void move(UUID transactionId, Wallet from, Wallet to, long amount) {
        from.debit(amount);
        to.credit(amount);
        entries.save(LedgerEntry.of(transactionId, from.getId(), Direction.DEBIT, amount, from.getBalance()));
        entries.save(LedgerEntry.of(transactionId, to.getId(), Direction.CREDIT, amount, to.getBalance()));
    }
}
