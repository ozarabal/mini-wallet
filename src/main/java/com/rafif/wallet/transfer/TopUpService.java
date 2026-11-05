package com.rafif.wallet.transfer;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.RequestHasher;
import com.rafif.wallet.wallet.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Entry point for top-ups: validate, then run the top-up at most once per Idempotency-Key. */
@Service
public class TopUpService {

    private final WalletRepository wallets;
    private final IdempotencyService idempotency;
    private final TransactionExecutor executor;

    public TopUpService(WalletRepository wallets, IdempotencyService idempotency, TransactionExecutor executor) {
        this.wallets = wallets;
        this.idempotency = idempotency;
        this.executor = executor;
    }

    public TransactionResult topUp(String idempotencyKey, UUID walletId, long amount) {
        if (SystemWallets.isCashIn(walletId)) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "Cannot top up a system cash-in wallet");
        }
        // The idempotency scope is the owner of the wallet being topped up.
        String ownerId = wallets.findById(walletId)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + walletId + " not found"))
                .getOwnerId();

        String hash = RequestHasher.sha256("TOPUP|" + walletId + "|" + amount);

        return idempotency.run(ownerId, idempotencyKey, hash,
                () -> executor.topUp(idempotencyKey, hash, walletId, amount));
    }
}
