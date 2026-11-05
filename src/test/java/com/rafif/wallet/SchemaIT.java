package com.rafif.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rafif.wallet.transfer.SystemWallets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    /** The migrations (V1 + V2) and SystemWallets in the code must describe the same set of wallets. */
    @Test
    void migrationsCreateExactlyTheCashInWalletsTheCodeKnows() {
        List<UUID> inDatabase = jdbc.queryForList("SELECT id FROM wallets WHERE owner_id = 'SYSTEM'", UUID.class);

        assertThat(inDatabase).hasSize(SystemWallets.COUNT);
        assertThat(inDatabase).containsExactlyInAnyOrderElementsOf(SystemWallets.CASH_IN_IDS);
    }

    @Test
    void everyCashInWalletHasFundsToTopUpFrom() {
        for (UUID id : SystemWallets.CASH_IN_IDS) {
            Long balance = jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", Long.class, id);
            assertThat(balance).as("cash-in wallet %s", id).isPositive();
        }
    }

    @Test
    void databaseRejectsNegativeBalance() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO wallets (id, owner_id, balance) VALUES (?, 'x', -1)", UUID.randomUUID()))
                .hasMessageContaining("wallets_balance_check");
    }
}
