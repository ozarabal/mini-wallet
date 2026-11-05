package com.rafif.wallet.transfer;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test helpers that talk to the database directly, so tests do not depend on the code under test. */
public class TransferTestSupport {

    private final JdbcTemplate jdbc;

    public TransferTestSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID wallet(String owner, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO wallets (id, owner_id, balance) VALUES (?, ?, ?)", id, owner, balance);
        return id;
    }

    public long balance(UUID walletId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", Long.class, walletId);
    }

    public int ledgerRows(UUID walletId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE wallet_id = ?", Integer.class, walletId);
    }

    public int transactionRows(String owner) {
        return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE owner_id = ?", Integer.class, owner);
    }

    /** Sum of credits minus sum of debits for one wallet; must equal its balance (when it started at 0). */
    public long ledgerNet(UUID walletId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
                        + "FROM ledger_entries WHERE wallet_id = ?", Long.class, walletId);
    }
}
