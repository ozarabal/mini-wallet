package com.rafif.wallet.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Checks the rules that must hold after ANY sequence of operations, reading straight from the database:
 * <ol>
 *   <li>Money is conserved: the wallets together hold exactly what they held at the start.</li>
 *   <li>Each wallet's balance changed by exactly (credits - debits) written since the snapshot.</li>
 *   <li>Each transaction's debits equal its credits, and none is left PENDING.</li>
 *   <li>No negative balance.</li>
 *   <li>Each wallet's new entries replay in order: every {@code balance_after} matches the running total.</li>
 *   <li>Every completed transaction has exactly one outbox event.</li>
 * </ol>
 * The set of wallets must be <b>closed</b>: every transaction touching them must only touch wallets in the set
 * (a top-up therefore needs the cash-in wallet in the set). Take the snapshot BEFORE the operations start.
 * Wallets may already have history (the cash-in wallet does): only what happens after the snapshot is checked.
 */
public class LedgerInvariants {

    /** What one wallet looked like when the snapshot was taken. */
    public record WalletState(long balance, long ledgerNet, int entryCount) {
    }

    public record Snapshot(Map<UUID, WalletState> wallets) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Records the current state of each wallet. Call before the operations under test. */
    public Snapshot snapshot(UUID... walletIds) {
        Map<UUID, WalletState> states = new LinkedHashMap<>();
        for (UUID id : walletIds) {
            MapSqlParameterSource params = new MapSqlParameterSource("id", id);
            long balance = jdbc.queryForObject("SELECT balance FROM wallets WHERE id = :id", params, Long.class);
            long net = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
                            + "FROM ledger_entries WHERE wallet_id = :id", params, Long.class);
            int count = jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE wallet_id = :id", params, Integer.class);
            states.put(id, new WalletState(balance, net, count));
        }
        return new Snapshot(states);
    }

    public void assertHolds(Snapshot start) {
        assertThat(check(start)).as("ledger invariant violations").isEmpty();
    }

    /** Returns a description of every violated invariant (empty list = all hold). */
    public List<String> check(Snapshot start) {
        List<String> violations = new ArrayList<>();
        MapSqlParameterSource ids = new MapSqlParameterSource("ids", start.wallets().keySet());

        Map<UUID, Long> balances = new HashMap<>();
        jdbc.query("SELECT id, balance FROM wallets WHERE id IN (:ids)", ids,
                rs -> {
                    balances.put(rs.getObject("id", UUID.class), rs.getLong("balance"));
                });

        Map<UUID, Long> net = new HashMap<>();
        jdbc.query("SELECT wallet_id, SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net "
                        + "FROM ledger_entries WHERE wallet_id IN (:ids) GROUP BY wallet_id", ids,
                rs -> {
                    net.put(rs.getObject("wallet_id", UUID.class), rs.getLong("net"));
                });

        // 1. money is conserved
        long startTotal = start.wallets().values().stream().mapToLong(WalletState::balance).sum();
        long nowTotal = balances.values().stream().mapToLong(Long::longValue).sum();
        if (startTotal != nowTotal) {
            violations.add("money not conserved: started with " + startTotal + " now " + nowTotal);
        }

        for (Map.Entry<UUID, WalletState> wallet : start.wallets().entrySet()) {
            UUID id = wallet.getKey();
            WalletState before = wallet.getValue();
            long balance = balances.getOrDefault(id, Long.MIN_VALUE);
            // 4. never negative
            if (balance < 0) {
                violations.add("wallet " + id + " has negative balance " + balance);
            }
            // 2. balance moved exactly as much as the new ledger entries say
            long ledgerChange = net.getOrDefault(id, 0L) - before.ledgerNet();
            long expected = before.balance() + ledgerChange;
            if (balance != expected) {
                violations.add("wallet " + id + " balance " + balance + " but start + ledger change = " + expected);
            }
        }

        // 3. every transaction balances and is completed
        jdbc.query("""
                SELECT t.id, t.status,
                       COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE 0 END), 0) AS debits,
                       COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE 0 END), 0) AS credits
                FROM transactions t LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                WHERE t.id IN (SELECT transaction_id FROM ledger_entries WHERE wallet_id IN (:ids))
                GROUP BY t.id, t.status
                """, ids, rs -> {
            long debits = rs.getLong("debits");
            long credits = rs.getLong("credits");
            if (debits != credits) {
                violations.add("transaction " + rs.getObject("id") + " debits " + debits + " != credits " + credits);
            }
            if (!"COMPLETED".equals(rs.getString("status"))) {
                violations.add("transaction " + rs.getObject("id") + " is " + rs.getString("status"));
            }
        });

        // 6. every completed transaction was announced exactly once (the outbox row is written in the same database
        //    transaction as the ledger entries, so these two can never disagree).
        jdbc.query("""
                SELECT t.id, count(o.id) AS events
                FROM transactions t LEFT JOIN outbox_events o ON o.aggregate_id = t.id
                WHERE t.status = 'COMPLETED'
                  AND t.id IN (SELECT transaction_id FROM ledger_entries WHERE wallet_id IN (:ids))
                GROUP BY t.id
                HAVING count(o.id) <> 1
                """, ids, rs -> {
            violations.add("transaction " + rs.getObject("id") + " has " + rs.getLong("events")
                    + " outbox events instead of 1");
        });

        // 5. new entries replay in order. Entries of one wallet are written while holding its row lock,
        //    so (created_at) is their true order; the id only breaks the (practically impossible) tie.
        //    The first `entryCount` entries of each wallet existed before the snapshot and are skipped.
        Map<UUID, Long> running = new HashMap<>();
        Map<UUID, Integer> seen = new HashMap<>();
        start.wallets().forEach((id, state) -> running.put(id, state.balance()));
        jdbc.query("SELECT wallet_id, direction, amount, balance_after FROM ledger_entries "
                        + "WHERE wallet_id IN (:ids) ORDER BY wallet_id, created_at, id", ids,
                rs -> {
                    UUID id = rs.getObject("wallet_id", UUID.class);
                    int index = seen.merge(id, 1, Integer::sum);
                    if (index <= start.wallets().get(id).entryCount()) {
                        return; // history from before the snapshot
                    }
                    long delta = "CREDIT".equals(rs.getString("direction")) ? rs.getLong("amount") : -rs.getLong("amount");
                    long after = running.merge(id, delta, Long::sum);
                    if (after != rs.getLong("balance_after")) {
                        violations.add("wallet " + id + " entry says balance_after " + rs.getLong("balance_after")
                                + " but running total is " + after);
                    }
                });

        return violations;
    }
}
