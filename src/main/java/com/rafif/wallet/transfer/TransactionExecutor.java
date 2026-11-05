package com.rafif.wallet.transfer;

import com.rafif.wallet.ledger.Transaction;
import com.rafif.wallet.ledger.TransactionRepository;
import com.rafif.wallet.ledger.TransactionType;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core. Each method is ONE database transaction: if any step fails (for example
 * insufficient funds, or a duplicate idempotency key) the transaction row, the balance changes and the
 * ledger entries are all rolled back together.
 * <p>
 * It deliberately does not handle idempotency replays: a failed database transaction cannot be reused,
 * so the retry logic lives outside, in {@link IdempotencyService}.
 */
@Component
class TransactionExecutor {

    private final WalletLocker walletLocker;
    private final TransactionRepository transactions;
    private final MoneyMover moneyMover;

    TransactionExecutor(WalletLocker walletLocker, TransactionRepository transactions, MoneyMover moneyMover) {
        this.walletLocker = walletLocker;
        this.transactions = transactions;
        this.moneyMover = moneyMover;
    }

    @Transactional
    TransactionResponse transfer(String idempotencyKey, String requestHash, UUID fromId, UUID toId, long amount) {
        // 1. Lock both wallets in ascending id order (see WalletRepository for why).
        WalletLocker.Pair locked = walletLocker.lock(fromId, toId);

        // 2. Record the business transaction. The unique (owner_id, idempotency_key) constraint is checked here.
        Transaction tx = transactions.saveAndFlush(Transaction.start(
                TransactionType.TRANSFER, locked.from().getOwnerId(), idempotencyKey, requestHash));

        // 3. Move the money and write the two ledger entries.
        moneyMover.move(tx.getId(), locked.from(), locked.to(), amount);
        tx.markCompleted();

        return TransactionResponse.of(tx, fromId, toId, amount);
    }

    /** A top-up is a transfer from the system cash-in wallet, so the ledger always balances. */
    @Transactional
    TransactionResponse topUp(String idempotencyKey, String requestHash, UUID walletId, long amount) {
        WalletLocker.Pair locked = walletLocker.lock(SystemWallets.CASH_IN_ID, walletId);

        // The owner of a top-up is the owner of the wallet being topped up.
        Transaction tx = transactions.saveAndFlush(Transaction.start(
                TransactionType.TOPUP, locked.to().getOwnerId(), idempotencyKey, requestHash));

        moneyMover.move(tx.getId(), locked.from(), locked.to(), amount);
        tx.markCompleted();

        return TransactionResponse.of(tx, SystemWallets.CASH_IN_ID, walletId, amount);
    }
}
