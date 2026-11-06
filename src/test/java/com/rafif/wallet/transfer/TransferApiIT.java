package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.TestUser;
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

class TransferApiIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    TransferTestSupport db;
    TestUser user;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        user = newUser();
    }

    private <T> ResponseEntity<T> post(String key, Object body, Class<T> type) {
        HttpHeaders headers = user.headers();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), type);
    }

    private static Map<String, Object> body(UUID from, UUID to, long amount) {
        return Map.of("fromWalletId", from, "toWalletId", to, "amount", amount);
    }

    @Test
    void transferMovesMoneyAndWritesBalancedLedgerEntries() {
        UUID alice = db.wallet(user.id().toString(), 1_000);
        UUID bob = db.wallet(user.id().toString(), 0);

        ResponseEntity<TransactionResponse> response =
                post(UUID.randomUUID().toString(), body(alice, bob, 300), TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("COMPLETED");
        assertThat(db.balance(alice)).isEqualTo(700);
        assertThat(db.balance(bob)).isEqualTo(300);

        // one DEBIT and one CREDIT, same amount, recording the balance after the change
        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT direction, amount, balance_after, wallet_id FROM ledger_entries WHERE transaction_id = ? "
                        + "ORDER BY direction", response.getBody().transactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("direction", "CREDIT")
                .containsEntry("amount", 300L).containsEntry("balance_after", 300L).containsEntry("wallet_id", bob);
        assertThat(entries.get(1)).containsEntry("direction", "DEBIT")
                .containsEntry("amount", 300L).containsEntry("balance_after", 700L).containsEntry("wallet_id", alice);
    }

    @Test
    void insufficientFundsReturns422AndWritesNothing() {
        UUID carol = db.wallet(user.id().toString(), 100);
        UUID dave = db.wallet(user.id().toString(), 0);

        ResponseEntity<Map> response = post(UUID.randomUUID().toString(), body(carol, dave, 101), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_FUNDS");
        assertThat(db.balance(carol)).isEqualTo(100);
        assertThat(db.balance(dave)).isZero();
        assertThat(db.ledgerRows(carol)).isZero();
        assertThat(db.transactionRows(user.id().toString())).isZero(); // the transaction row was rolled back too
    }

    @Test
    void transferToSelfReturns400() {
        UUID erin = db.wallet(user.id().toString(), 500);

        ResponseEntity<Map> response = post(UUID.randomUUID().toString(), body(erin, erin, 10), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "SELF_TRANSFER");
        assertThat(db.balance(erin)).isEqualTo(500);
    }

    @Test
    void unknownWalletReturns404() {
        UUID frank = db.wallet(user.id().toString(), 500);

        ResponseEntity<Map> response = post(UUID.randomUUID().toString(), body(frank, UUID.randomUUID(), 10), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "WALLET_NOT_FOUND");
        assertThat(db.balance(frank)).isEqualTo(500);
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        ResponseEntity<Map> response = post(null, body(UUID.randomUUID(), UUID.randomUUID(), 10), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_MISSING");
    }

    @Test
    void zeroOrNegativeAmountReturns400() {
        UUID a = db.wallet(user.id().toString(), 500);
        UUID b = db.wallet(user.id().toString(), 0);

        for (long bad : new long[] {0, -5}) {
            ResponseEntity<Map> response = post(UUID.randomUUID().toString(), body(a, b, bad), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
        }
        assertThat(db.balance(a)).isEqualTo(500);
    }
}
