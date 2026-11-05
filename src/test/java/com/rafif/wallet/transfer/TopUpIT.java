package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.LedgerInvariants;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    TransferTestSupport db;
    LedgerInvariants invariants;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        invariants = new LedgerInvariants(jdbc);
    }

    private <T> ResponseEntity<T> topUp(UUID walletId, String key, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.postForEntity("/api/v1/wallets/" + walletId + "/topups", new HttpEntity<>(body, headers), type);
    }

    @Test
    void topUpCreditsWalletAndDebitsCashInWallet() {
        UUID wallet = db.wallet("topup-1", 0);
        long cashInBefore = db.balance(SystemWallets.CASH_IN_ID);

        ResponseEntity<TransactionResponse> response =
                topUp(wallet, UUID.randomUUID().toString(), Map.of("amount", 5_000), TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().type()).isEqualTo("TOPUP");
        assertThat(response.getBody().fromWalletId()).isEqualTo(SystemWallets.CASH_IN_ID);
        assertThat(db.balance(wallet)).isEqualTo(5_000);
        assertThat(db.balance(SystemWallets.CASH_IN_ID)).isEqualTo(cashInBefore - 5_000); // money is conserved

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT direction, amount, wallet_id FROM ledger_entries WHERE transaction_id = ? ORDER BY direction",
                response.getBody().transactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("direction", "CREDIT").containsEntry("wallet_id", wallet);
        assertThat(entries.get(1)).containsEntry("direction", "DEBIT")
                .containsEntry("wallet_id", SystemWallets.CASH_IN_ID);
    }

    @Test
    void toppedUpMoneyCanBeTransferred() {
        UUID from = db.wallet("topup-2", 0);
        UUID to = db.wallet("topup-3", 0);
        topUp(from, UUID.randomUUID().toString(), Map.of("amount", 1_000), TransactionResponse.class);

        HttpHeaders headers = new HttpHeaders();
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
        UUID wallet = db.wallet("topup-4", 0);
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
        UUID wallet = db.wallet("topup-5", 0);

        ResponseEntity<Map> response = topUp(wallet, null, Map.of("amount", 100), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_MISSING");
    }

    @Test
    void cannotTopUpTheCashInWallet() {
        ResponseEntity<Map> response = topUp(SystemWallets.CASH_IN_ID, UUID.randomUUID().toString(),
                Map.of("amount", 100), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** All top-ups lock the same cash-in row, so they are serialized: none may be lost. */
    @Test
    void concurrentTopUpsAreAllApplied() throws Exception {
        UUID wallet = db.wallet("topup-6", 0);
        var start = invariants.snapshot(wallet, SystemWallets.CASH_IN_ID);

        // each call uses its own random key, so all 20 are different requests
        ConcurrentRunner.runRepeated(20, () -> topUpService.topUp(UUID.randomUUID().toString(), wallet, 50));

        assertThat(db.balance(wallet)).isEqualTo(1_000);
        assertThat(db.ledgerRows(wallet)).isEqualTo(20);
        assertThat(db.ledgerNet(wallet)).isEqualTo(1_000); // balance equals the sum of its ledger entries
        invariants.assertHolds(start);
    }
}
