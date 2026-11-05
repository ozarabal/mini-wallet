package com.rafif.wallet.transfer;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.wallet.Wallet;
import com.rafif.wallet.wallet.WalletRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the two wallets involved in a money movement. Both transfers and top-ups go through here,
 * so the ascending-id lock order is guaranteed in one place.
 */
@Component
class WalletLocker {

    /** The wallets of one movement, already locked. */
    record Pair(Wallet from, Wallet to) {
    }

    private final WalletRepository wallets;

    WalletLocker(WalletRepository wallets) {
        this.wallets = wallets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Pair lock(UUID fromId, UUID toId) {
        List<Wallet> locked = wallets.findAllByIdForUpdate(List.of(fromId, toId));
        return new Pair(find(locked, fromId), find(locked, toId));
    }

    private static Wallet find(List<Wallet> locked, UUID id) {
        return locked.stream()
                .filter(w -> w.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + id + " not found"));
    }
}
