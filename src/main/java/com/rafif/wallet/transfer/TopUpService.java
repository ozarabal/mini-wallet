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

/**
 * A top-up is a transfer from the system cash-in wallet, so the ledger always balances
 * (the money comes from somewhere) and it reuses exactly the same locking and money-moving code.
 */
@Service
public class TopUpService {

    private final WalletLocker walletLocker;
    private final TransactionRepository transactions;
    private final MoneyMover moneyMover;

    public TopUpService(WalletLocker walletLocker, TransactionRepository transactions, MoneyMover moneyMover) {
        this.walletLocker = walletLocker;
        this.transactions = transactions;
        this.moneyMover = moneyMover;
    }

    @Transactional
    public TransactionResponse topUp(String idempotencyKey, UUID walletId, long amount) {
        if (walletId.equals(SystemWallets.CASH_IN_ID)) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "Cannot top up the system cash-in wallet");
        }

        WalletLocker.Pair locked = walletLocker.lock(SystemWallets.CASH_IN_ID, walletId);

        // The owner of a top-up is the owner of the wallet being topped up.
        String hash = RequestHasher.sha256("TOPUP|" + walletId + "|" + amount);
        Transaction tx = transactions.saveAndFlush(
                Transaction.start(TransactionType.TOPUP, locked.to().getOwnerId(), idempotencyKey, hash));

        moneyMover.move(tx.getId(), locked.from(), locked.to(), amount);
        tx.markCompleted();

        return TransactionResponse.of(tx, SystemWallets.CASH_IN_ID, walletId, amount);
    }
}
