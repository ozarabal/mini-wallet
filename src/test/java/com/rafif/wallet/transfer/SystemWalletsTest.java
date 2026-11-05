package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain unit test (no Spring, no database): how a top-up picks its cash-in wallet. */
class SystemWalletsTest {

    @Test
    void thereAreSixteenDistinctCashInWallets() {
        assertThat(SystemWallets.CASH_IN_IDS).hasSize(16).doesNotHaveDuplicates();
    }

    @Test
    void recognisesEveryCashInWalletAndNothingElse() {
        SystemWallets.CASH_IN_IDS.forEach(id -> assertThat(SystemWallets.isCashIn(id)).isTrue());
        assertThat(SystemWallets.isCashIn(UUID.randomUUID())).isFalse();
    }

    @Test
    void sameKeyAlwaysPicksTheSameWallet() {
        for (int i = 0; i < 100; i++) {
            String key = "retry-me-" + i;
            assertThat(SystemWallets.cashInFor(key)).isEqualTo(SystemWallets.cashInFor(key));
        }
    }

    @Test
    void alwaysPicksAKnownCashInWalletEvenForOddKeys() {
        // negative hash codes must not produce a negative index
        for (String key : new String[] {"", "a", "Aa", "BB", "polygenelubricants", UUID.randomUUID().toString()}) {
            assertThat(SystemWallets.isCashIn(SystemWallets.cashInFor(key))).as(key).isTrue();
        }
    }

    /** Fixed keys, so the result is the same on every run: this cannot be flaky. */
    @Test
    void spreadsKeysEvenlyOverAllWallets() {
        Map<UUID, Integer> perWallet = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            perWallet.merge(SystemWallets.cashInFor("key-" + i), 1, Integer::sum);
        }

        assertThat(perWallet).hasSize(16); // every wallet is used
        // a perfect spread is 625 each; allow +-30% so only a clearly broken spread fails
        assertThat(perWallet.values()).allMatch(count -> count >= 437 && count <= 813);
    }

    @Test
    void spreadsUuidKeysEvenlyToo() {
        // the realistic case: clients send random UUIDs. Derived from a counter so the keys are fixed.
        Map<UUID, Integer> perWallet = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            String key = new UUID(i * 0x9E3779B97F4A7C15L, i).toString();
            perWallet.merge(SystemWallets.cashInFor(key), 1, Integer::sum);
        }

        assertThat(perWallet).hasSize(16);
        assertThat(perWallet.values()).allMatch(count -> count >= 437 && count <= 813);
    }
}
