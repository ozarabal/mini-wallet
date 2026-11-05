package com.rafif.wallet.transfer;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.RequestHasher;
import com.rafif.wallet.wallet.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Entry point for transfers: validate, then run the transfer at most once per Idempotency-Key. */
@Service
public class TransferService {

    private final WalletRepository wallets;
    private final IdempotencyService idempotency;
    private final TransactionExecutor executor;

    public TransferService(WalletRepository wallets, IdempotencyService idempotency, TransactionExecutor executor) {
        this.wallets = wallets;
        this.idempotency = idempotency;
        this.executor = executor;
    }

    public TransactionResult transfer(String idempotencyKey, UUID fromId, UUID toId, long amount) {
        if (fromId.equals(toId)) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "Cannot transfer to the same wallet");
        }
        // The idempotency scope is the owner of the source wallet. A plain read is enough here:
        // the owner of a wallet never changes, so no lock is needed for this lookup.
        String ownerId = wallets.findById(fromId)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + fromId + " not found"))
                .getOwnerId();

        // The type is part of the hash so the same key cannot silently mean a transfer and a top-up.
        String hash = RequestHasher.sha256("TRANSFER|" + fromId + "|" + toId + "|" + amount);

        return idempotency.run(ownerId, idempotencyKey, hash,
                () -> executor.transfer(idempotencyKey, hash, fromId, toId, amount));
    }
}
