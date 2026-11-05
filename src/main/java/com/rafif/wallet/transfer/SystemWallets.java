package com.rafif.wallet.transfer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The system "cash-in" wallets: the source of every top-up.
 * <p>
 * There are several of them on purpose. A top-up locks its cash-in wallet for the length of the transaction, so with
 * one cash-in wallet every top-up in the whole system would take turns on that single row. With
 * {@value #COUNT} wallets, top-ups only wait for each other when they happen to pick the same one.
 * <p>
 * Keep in mind when checking "total money is constant": the cash-in wallets are part of the system, so the sum has
 * to include ALL of them.
 */
public final class SystemWallets {

    public static final int COUNT = 16;

    /** Ids ...0001 to ...0010 (hex). Created by Flyway V1 (the first) and V2 (the other fifteen). */
    public static final List<UUID> CASH_IN_IDS = IntStream.rangeClosed(1, COUNT)
            .mapToObj(n -> UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n)))
            .toList();

    private static final Set<UUID> CASH_IN_SET = Set.copyOf(CASH_IN_IDS);

    private SystemWallets() {
    }

    public static boolean isCashIn(UUID walletId) {
        return CASH_IN_SET.contains(walletId);
    }

    /**
     * Picks the cash-in wallet for a top-up from its idempotency key. The same key always gives the same wallet
     * (String.hashCode is defined by the Java spec, so this is stable across restarts and instances), and different
     * keys spread over all the wallets.
     */
    public static UUID cashInFor(String idempotencyKey) {
        return CASH_IN_IDS.get(Math.floorMod(idempotencyKey.hashCode(), COUNT));
    }
}
