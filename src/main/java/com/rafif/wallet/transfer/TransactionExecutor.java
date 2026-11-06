package com.rafif.wallet.transfer;

import com.rafif.wallet.ledger.Transaction;
import com.rafif.wallet.ledger.TransactionRepository;
import com.rafif.wallet.ledger.TransactionType;
import com.rafif.wallet.outbox.OutboxEventType;
import com.rafif.wallet.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core. Each method is ONE database transaction: if any step fails (for example
 * insufficient funds, or a duplicate idempotency key) the transaction row, the balance changes, the
 * ledger entries and the outbox event are all rolled back together.
 * <p>
 * It deliberately does not handle idempotency replays: a failed database transaction cannot be reused,
 * so the retry logic lives outside, in {@link IdempotencyService}.
 */
@Component
class TransactionExecutor {

    private final WalletLocker walletLocker;
    private final TransactionRepository transactions;
    private final MoneyMover moneyMover;

    private final OutboxWriter outboxWriter;

    TransactionExecutor(WalletLocker walletLocker, TransactionRepository transactions, MoneyMover moneyMover,
                        OutboxWriter outboxWriter) {
        this.walletLocker = walletLocker;
        this.transactions = transactions;
        this.moneyMover = moneyMover;
        this.outboxWriter = outboxWriter;
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

        // 4. Announce it. Same transaction: the event exists if and only if the money moved. Kafka key = the paying
        //    wallet, so its transfers are published (and consumed) in the order they happened.
        outboxWriter.record(OutboxEventType.TRANSFER_COMPLETED, tx.getId(), fromId, toId, amount, fromId.toString());

        return TransactionResponse.of(tx, fromId, toId, amount);
    }

    /**
     * A top-up is a transfer from a system cash-in wallet, so the ledger always balances. Which of the cash-in
     * wallets is decided by the idempotency key, to spread top-ups over several rows instead of queueing them all
     * on one. The locking is unchanged: two wallets, locked in ascending id order.
     */
    @Transactional
    TransactionResponse topUp(String idempotencyKey, String requestHash, UUID walletId, long amount) {
        UUID cashIn = SystemWallets.cashInFor(idempotencyKey);
        WalletLocker.Pair locked = walletLocker.lock(cashIn, walletId);

        // The owner of a top-up is the owner of the wallet being topped up.
        Transaction tx = transactions.saveAndFlush(Transaction.start(
                TransactionType.TOPUP, locked.to().getOwnerId(), idempotencyKey, requestHash));

        moneyMover.move(tx.getId(), locked.from(), locked.to(), amount);
        tx.markCompleted();

        // The paying wallet is one of 16 shared cash-in wallets, which says nothing useful about order, so a top-up
        // is keyed by the wallet that receives the money.
        outboxWriter.record(OutboxEventType.TOPUP_COMPLETED, tx.getId(), cashIn, walletId, amount, walletId.toString());

        return TransactionResponse.of(tx, cashIn, walletId, amount);
    }
}
