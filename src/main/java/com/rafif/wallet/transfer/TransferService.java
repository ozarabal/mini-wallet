package com.rafif.wallet.transfer;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.RequestHasher;
import com.rafif.wallet.ledger.Transaction;
import com.rafif.wallet.ledger.TransactionRepository;
import com.rafif.wallet.ledger.TransactionType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final WalletLocker walletLocker;
    private final TransactionRepository transactions;
    private final MoneyMover moneyMover;

    public TransferService(WalletLocker walletLocker, TransactionRepository transactions, MoneyMover moneyMover) {
        this.walletLocker = walletLocker;
        this.transactions = transactions;
        this.moneyMover = moneyMover;
    }

    /**
     * Everything below happens in ONE database transaction: if any step fails (for example insufficient
     * funds), the transaction row, the balance changes and the ledger entries are all rolled back together.
     */
    @Transactional
    public TransactionResponse transfer(String idempotencyKey, UUID fromId, UUID toId, long amount) {
        if (fromId.equals(toId)) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "Cannot transfer to the same wallet");
        }

        // 1. Lock both wallets in ascending id order (see WalletRepository for why).
        WalletLocker.Pair locked = walletLocker.lock(fromId, toId);

        // 2. Record the business transaction. The unique (owner_id, idempotency_key) constraint is checked here.
        String hash = RequestHasher.sha256("TRANSFER|" + fromId + "|" + toId + "|" + amount);
        Transaction tx = transactions.saveAndFlush(
                Transaction.start(TransactionType.TRANSFER, locked.from().getOwnerId(), idempotencyKey, hash));

        // 3. Move the money and write the two ledger entries.
        moneyMover.move(tx.getId(), locked.from(), locked.to(), amount);
        tx.markCompleted();

        return TransactionResponse.of(tx, fromId, toId, amount);
    }
}
