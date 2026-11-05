package com.rafif.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationSeedsCashInWallet() {
        Long balance = jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE id = '00000000-0000-0000-0000-000000000001'", Long.class);
        assertThat(balance).isPositive();
    }

    @Test
    void databaseRejectsNegativeBalance() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO wallets (id, owner_id, balance) VALUES (?, 'x', -1)", UUID.randomUUID()))
                .hasMessageContaining("wallets_balance_check");
    }
}
