package com.rafif.wallet.wallet;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(UUID id, String ownerId, String currency, long balance, Instant createdAt) {

    static WalletResponse from(Wallet w) {
        return new WalletResponse(w.getId(), w.getOwnerId(), w.getCurrency(), w.getBalance(), w.getCreatedAt());
    }
}
