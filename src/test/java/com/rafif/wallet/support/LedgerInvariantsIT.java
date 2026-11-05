package com.rafif.wallet.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests the checker itself. A checker that cannot see corruption would make every test that uses it
 * meaningless, so here we corrupt the database on purpose and expect each rule to notice.
 */
class LedgerInvariantsIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransferService transferService;

    TransferTestSupport db;
    LedgerInvariants invariants;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        invariants = new LedgerInvariants(jdbc);
    }

    @Test
    void holdsAfterNormalTransfers() {
        UUID a = db.wallet("inv-a", 1_000);
        UUID b = db.wallet("inv-b", 500);
        var start = invariants.snapshot(a, b);

        transferService.transfer(UUID.randomUUID().toString(), a, b, 300);
        transferService.transfer(UUID.randomUUID().toString(), b, a, 50);

        assertThat(invariants.check(start)).isEmpty();
    }

    /** The cash-in wallet is like this: it has history from earlier operations before any test starts. */
    @Test
    void onlyChecksWhatHappenedAfterTheSnapshotForWalletsWithHistory() {
        UUID a = db.wallet("inv-i", 1_000);
        UUID b = db.wallet("inv-j", 0);
        transferService.transfer(UUID.randomUUID().toString(), a, b, 100); // history exists before the snapshot
        var start = invariants.snapshot(a, b);

        transferService.transfer(UUID.randomUUID().toString(), a, b, 50);

        assertThat(invariants.check(start)).isEmpty();
    }

    @Test
    void detectsBalanceChangedWithoutLedgerEntry() {
        UUID a = db.wallet("inv-c", 1_000);
        UUID b = db.wallet("inv-d", 0);
        var start = invariants.snapshot(a, b);
        transferService.transfer(UUID.randomUUID().toString(), a, b, 100);

        jdbc.update("UPDATE wallets SET balance = balance + 7 WHERE id = ?", a); // money from nowhere

        assertThat(invariants.check(start))
                .anyMatch(v -> v.contains("money not conserved"))
                .anyMatch(v -> v.contains("start + ledger"));
    }

    @Test
    void detectsUnbalancedTransaction() {
        UUID a = db.wallet("inv-e", 1_000);
        var start = invariants.snapshot(a);
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, type, status, idempotency_key, request_hash, owner_id) "
                + "VALUES (?, 'TRANSFER', 'COMPLETED', 'bad', 'h', 'inv-e')", tx);
        jdbc.update("INSERT INTO ledger_entries (id, transaction_id, wallet_id, direction, amount, balance_after) "
                + "VALUES (?, ?, ?, 'DEBIT', 100, 900)", UUID.randomUUID(), tx, a); // a debit with no credit
        jdbc.update("UPDATE wallets SET balance = 900 WHERE id = ?", a);

        assertThat(invariants.check(start)).anyMatch(v -> v.contains("debits 100 != credits 0"));
    }

    @Test
    void detectsPendingTransactionLeftBehind() {
        UUID a = db.wallet("inv-f", 1_000);
        var start = invariants.snapshot(a);
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, type, status, idempotency_key, request_hash, owner_id) "
                + "VALUES (?, 'TRANSFER', 'PENDING', 'stuck', 'h', 'inv-f')", tx);
        jdbc.update("INSERT INTO ledger_entries (id, transaction_id, wallet_id, direction, amount, balance_after) "
                + "VALUES (?, ?, ?, 'CREDIT', 5, 1005)", UUID.randomUUID(), tx, a);
        jdbc.update("UPDATE wallets SET balance = 1005 WHERE id = ?", a);

        assertThat(invariants.check(start)).anyMatch(v -> v.contains("is PENDING"));
    }

    @Test
    void detectsWrongBalanceAfterInAnEntry() {
        UUID a = db.wallet("inv-g", 1_000);
        UUID b = db.wallet("inv-h", 0);
        var start = invariants.snapshot(a, b);
        transferService.transfer(UUID.randomUUID().toString(), a, b, 100);

        jdbc.update("UPDATE ledger_entries SET balance_after = balance_after + 1 WHERE wallet_id = ?", b);

        assertThat(invariants.check(start)).anyMatch(v -> v.contains("balance_after"));
    }
}
