package com.rafif.wallet.transfer;

import java.util.UUID;

public final class SystemWallets {

    /** The "cash-in" wallet seeded by Flyway V1. Every top-up is a transfer out of this wallet. */
    public static final UUID CASH_IN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemWallets() {
    }
}
