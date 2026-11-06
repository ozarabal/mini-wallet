package com.rafif.wallet.wallet;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final WalletRepository wallets;

    public WalletService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    /** The new wallet belongs to the caller. There is no way to create a wallet for someone else. */
    @Transactional
    public WalletResponse create(UUID callerId) {
        Wallet saved = wallets.save(Wallet.create(callerId.toString()));
        return WalletResponse.from(saved);
    }

    /** Only the owner may see a wallet's balance. */
    @Transactional(readOnly = true)
    public WalletResponse get(UUID callerId, UUID id) {
        Wallet wallet = wallets.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + id + " not found"));
        wallet.requireOwnedBy(callerId);
        return WalletResponse.from(wallet);
    }
}
