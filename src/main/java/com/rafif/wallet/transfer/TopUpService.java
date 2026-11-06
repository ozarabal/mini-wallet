package com.rafif.wallet.transfer;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.RequestHasher;
import com.rafif.wallet.wallet.Wallet;
import com.rafif.wallet.wallet.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Entry point for top-ups: check the caller owns the wallet, then run it at most once per Idempotency-Key. */
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

    public TransactionResult topUp(UUID callerId, String idempotencyKey, UUID walletId, long amount) {
        if (SystemWallets.isCashIn(walletId)) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "Cannot top up a system cash-in wallet");
        }
        Wallet wallet = wallets.findById(walletId)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + walletId + " not found"));

        // You can only top up your own wallet.
        wallet.requireOwnedBy(callerId);

        // Because of the check above, the idempotency scope (the owner of the wallet) is exactly the caller.
        String ownerId = wallet.getOwnerId();

        String hash = RequestHasher.sha256("TOPUP|" + walletId + "|" + amount);

        return idempotency.run(ownerId, idempotencyKey, hash,
                () -> executor.topUp(idempotencyKey, hash, walletId, amount));
    }
}
