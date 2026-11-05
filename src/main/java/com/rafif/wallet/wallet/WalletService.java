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

    @Transactional
    public WalletResponse create(CreateWalletRequest request) {
        Wallet saved = wallets.save(Wallet.create(request.ownerId()));
        return WalletResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public WalletResponse get(UUID id) {
        Wallet wallet = wallets.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + id + " not found"));
        return WalletResponse.from(wallet);
    }
}
