package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.LedgerInvariants;
import com.rafif.wallet.support.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class TopUpIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TopUpService topUpService;
    @Autowired
    TransferService transferService;

    TransferTestSupport db;
    LedgerInvariants invariants;
    TestUser user;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        invariants = new LedgerInvariants(jdbc);
        user = newUser();
    }

    private <T> ResponseEntity<T> topUp(UUID walletId, String key, Object body, Class<T> type) {
        HttpHeaders headers = user.headers();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.postForEntity("/api/v1/wallets/" + walletId + "/topups", new HttpEntity<>(body, headers), type);
    }

    @Test
    void topUpCreditsWalletAndDebitsCashInWallet() {
        UUID wallet = db.wallet(user.id().toString(), 0);
        String key = UUID.randomUUID().toString();
        UUID cashIn = SystemWallets.cashInFor(key); // the idempotency key decides which cash-in wallet pays
        long cashInBefore = db.balance(cashIn);

        ResponseEntity<TransactionResponse> response =
                topUp(wallet, key, Map.of("amount", 5_000), TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().type()).isEqualTo("TOPUP");
        assertThat(response.getBody().fromWalletId()).isEqualTo(cashIn);
        assertThat(db.balance(wallet)).isEqualTo(5_000);
        assertThat(db.balance(cashIn)).isEqualTo(cashInBefore - 5_000); // money is conserved

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT direction, amount, wallet_id FROM ledger_entries WHERE transaction_id = ? ORDER BY direction",
                response.getBody().transactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("direction", "CREDIT").containsEntry("wallet_id", wallet);
        assertThat(entries.get(1)).containsEntry("direction", "DEBIT").containsEntry("wallet_id", cashIn);
    }

    @Test
    void toppedUpMoneyCanBeTransferred() {
        UUID from = db.wallet(user.id().toString(), 0);
        UUID to = db.wallet(user.id().toString(), 0);
        topUp(from, UUID.randomUUID().toString(), Map.of("amount", 1_000), TransactionResponse.class);

        HttpHeaders headers = user.headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<TransactionResponse> response = rest.postForEntity("/api/v1/transfers",
                new HttpEntity<>(Map.of("fromWalletId", from, "toWalletId", to, "amount", 400), headers),
                TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(db.balance(from)).isEqualTo(600);
        assertThat(db.balance(to)).isEqualTo(400);
    }

    @Test
    void unknownWalletReturns404() {
        ResponseEntity<Map> response =
                topUp(UUID.randomUUID(), UUID.randomUUID().toString(), Map.of("amount", 100), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "WALLET_NOT_FOUND");
    }

    @Test
    void zeroOrNegativeAmountReturns400() {
        UUID wallet = db.wallet(user.id().toString(), 0);
        for (long bad : new long[] {0, -100}) {
            ResponseEntity<Map> response =
                    topUp(wallet, UUID.randomUUID().toString(), Map.of("amount", bad), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
        }
        assertThat(db.balance(wallet)).isZero();
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        UUID wallet = db.wallet(user.id().toString(), 0);

        ResponseEntity<Map> response = topUp(wallet, null, Map.of("amount", 100), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_MISSING");
    }

    @Test
    void cannotTopUpAnyOfTheCashInWallets() {
        for (UUID cashIn : SystemWallets.CASH_IN_IDS) {
            ResponseEntity<Map> response =
                    topUp(cashIn, UUID.randomUUID().toString(), Map.of("amount", 100), Map.class);

            assertThat(response.getStatusCode()).as(cashIn.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /** Top-ups that pick the same cash-in wallet take turns on it: none may be lost. */
    @Test
    void concurrentTopUpsAreAllApplied() throws Exception {
        UUID wallet = db.wallet(user.id().toString(), 0);
        var start = invariants.snapshot(TransferTestSupport.withAllCashIn(wallet));

        // each call uses its own random key, so all 20 are different requests
        ConcurrentRunner.runRepeated(20, () -> topUpService.topUp(user.id(), UUID.randomUUID().toString(), wallet, 50));

        assertThat(db.balance(wallet)).isEqualTo(1_000);
        assertThat(db.ledgerRows(wallet)).isEqualTo(20);
        assertThat(db.ledgerNet(wallet)).isEqualTo(1_000); // balance equals the sum of its ledger entries
        invariants.assertHolds(start);
    }

    /** The point of having several cash-in wallets: top-ups really are paid from different ones. */
    @Test
    void topUpsAreSpreadOverSeveralCashInWallets() throws Exception {
        UUID wallet = db.wallet(user.id().toString(), 0);
        var start = invariants.snapshot(TransferTestSupport.withAllCashIn(wallet));

        // Fixed keys, so which cash-in wallet each one picks is fixed too: this test is deterministic.
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String key = "spread-" + i;
            tasks.add(() -> topUpService.topUp(user.id(), key, wallet, 10).transaction() != null);
        }
        ConcurrentRunner.runAll(tasks);

        Long cashInWalletsUsed = jdbc.queryForObject("""
                SELECT count(DISTINCT debit.wallet_id)
                FROM ledger_entries credit
                JOIN ledger_entries debit ON debit.transaction_id = credit.transaction_id AND debit.direction = 'DEBIT'
                WHERE credit.wallet_id = ? AND credit.direction = 'CREDIT'
                """, Long.class, wallet);
        assertThat(cashInWalletsUsed).isGreaterThanOrEqualTo(10); // 64 top-ups over 16 wallets: one wallet would be 1
        assertThat(db.balance(wallet)).isEqualTo(640);
        invariants.assertHolds(start);
    }

    /**
     * Top-ups lock {cash-in, wallet} and transfers lock {wallet, wallet}. All of them lock in ascending id order,
     * so mixing the two kinds, in both directions, must still never deadlock.
     */
    @Test
    void topUpsAndTransfersTogetherDoNotDeadlock() throws Exception {
        UUID a = db.wallet(user.id().toString(), 100_000);
        UUID b = db.wallet(user.id().toString(), 100_000);
        var start = invariants.snapshot(TransferTestSupport.withAllCashIn(a, b));

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> topUpService.topUp(user.id(), UUID.randomUUID().toString(), a, 5).transaction() != null);
            tasks.add(() -> topUpService.topUp(user.id(), UUID.randomUUID().toString(), b, 5).transaction() != null);
            tasks.add(() -> transferService.transfer(user.id(), UUID.randomUUID().toString(), a, b, 10).transaction() != null);
            tasks.add(() -> transferService.transfer(user.id(), UUID.randomUUID().toString(), b, a, 10).transaction() != null);
        }
        ConcurrentRunner.runAll(tasks); // a deadlock victim error or a timeout fails the test here

        assertThat(db.balance(a)).isEqualTo(100_500); // +100 top-ups of 5; the 100+100 transfers cancel out
        assertThat(db.balance(b)).isEqualTo(100_500);
        invariants.assertHolds(start);
    }
}
