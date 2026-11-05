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

class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransferService transferService;
    @Autowired
    TopUpService topUpService;

    TransferTestSupport db;
    LedgerInvariants invariants;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        invariants = new LedgerInvariants(jdbc);
    }

    private <T> ResponseEntity<T> transfer(String key, UUID from, UUID to, long amount, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return rest.postForEntity("/api/v1/transfers",
                new HttpEntity<>(Map.of("fromWalletId", from, "toWalletId", to, "amount", amount), headers), type);
    }

    private <T> ResponseEntity<T> topUp(String key, UUID wallet, long amount, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return rest.postForEntity("/api/v1/wallets/" + wallet + "/topups",
                new HttpEntity<>(Map.of("amount", amount), headers), type);
    }

    @Test
    void retryWithSameKeyAndBodyReturnsSameResultAndWritesNothingNew() {
        UUID from = db.wallet("idem-a", 1_000);
        UUID to = db.wallet("idem-b", 0);
        String key = UUID.randomUUID().toString();

        ResponseEntity<TransactionResponse> first = transfer(key, from, to, 300, TransactionResponse.class);
        ResponseEntity<TransactionResponse> retry = transfer(key, from, to, 300, TransactionResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().transactionId()).isEqualTo(first.getBody().transactionId());
        assertThat(retry.getBody()).isEqualTo(first.getBody()); // same body, field for field

        assertThat(db.balance(from)).isEqualTo(700); // applied once, not twice
        assertThat(db.balance(to)).isEqualTo(300);
        assertThat(db.ledgerRows(from)).isEqualTo(1);
        assertThat(db.ledgerRows(to)).isEqualTo(1);
        assertThat(db.transactionRows("idem-a")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentAmountReturns422() {
        UUID from = db.wallet("idem-c", 1_000);
        UUID to = db.wallet("idem-d", 0);
        String key = UUID.randomUUID().toString();
        transfer(key, from, to, 300, TransactionResponse.class);

        ResponseEntity<Map> response = transfer(key, from, to, 301, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(db.balance(from)).isEqualTo(700); // still only the first request applied
    }

    @Test
    void sameKeyWithDifferentDestinationReturns422() {
        UUID from = db.wallet("idem-e", 1_000);
        UUID to = db.wallet("idem-f", 0);
        UUID other = db.wallet("idem-g", 0);
        String key = UUID.randomUUID().toString();
        transfer(key, from, to, 300, TransactionResponse.class);

        ResponseEntity<Map> response = transfer(key, from, other, 300, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(db.balance(other)).isZero();
    }

    @Test
    void topUpRetryIsReplayedToo() {
        UUID wallet = db.wallet("idem-h", 0);
        String key = UUID.randomUUID().toString();

        ResponseEntity<TransactionResponse> first = topUp(key, wallet, 500, TransactionResponse.class);
        ResponseEntity<TransactionResponse> retry = topUp(key, wallet, 500, TransactionResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(db.balance(wallet)).isEqualTo(500);
        assertThat(db.ledgerRows(wallet)).isEqualTo(1);
    }

    @Test
    void sameKeyForTopUpAndTransferByTheSameOwnerReturns422() {
        UUID wallet = db.wallet("idem-i", 0);
        UUID other = db.wallet("idem-j", 0);
        String key = UUID.randomUUID().toString();
        topUp(key, wallet, 500, TransactionResponse.class);

        ResponseEntity<Map> response = transfer(key, wallet, other, 500, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(db.balance(wallet)).isEqualTo(500);
    }

    @Test
    void failedRequestDoesNotConsumeTheKey() {
        UUID from = db.wallet("idem-k", 0);
        UUID to = db.wallet("idem-l", 0);
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> failed = transfer(key, from, to, 100, Map.class);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failed.getBody()).containsEntry("code", "INSUFFICIENT_FUNDS");

        topUp(UUID.randomUUID().toString(), from, 100, TransactionResponse.class);
        ResponseEntity<TransactionResponse> retry = transfer(key, from, to, 100, TransactionResponse.class);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED); // applied now, nothing was stored before
        assertThat(db.balance(to)).isEqualTo(100);
    }

    @Test
    void concurrentTransfersWithTheSameKeyApplyExactlyOnce() throws Exception {
        UUID from = db.wallet("idem-m", 1_000);
        UUID to = db.wallet("idem-n", 0);
        var start = invariants.snapshot(from, to);
        String key = UUID.randomUUID().toString();

        List<TransactionResult> results =
                ConcurrentRunner.runRepeated(20, () -> transferService.transfer(key, from, to, 100));

        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1); // exactly one winner
        assertThat(results.stream().map(r -> r.transaction().transactionId()).distinct()).hasSize(1);
        assertThat(db.balance(from)).isEqualTo(900); // 100 moved once, not 20 times
        assertThat(db.balance(to)).isEqualTo(100);
        assertThat(db.ledgerRows(from)).isEqualTo(1);
        assertThat(db.transactionRows("idem-m")).isEqualTo(1);
        invariants.assertHolds(start);
    }

    @Test
    void concurrentTopUpsWithTheSameKeyApplyExactlyOnce() throws Exception {
        UUID wallet = db.wallet("idem-o", 0);
        var start = invariants.snapshot(wallet, SystemWallets.CASH_IN_ID);
        String key = UUID.randomUUID().toString();

        List<TransactionResult> results =
                ConcurrentRunner.runRepeated(20, () -> topUpService.topUp(key, wallet, 250));

        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(db.balance(wallet)).isEqualTo(250);
        assertThat(db.ledgerRows(wallet)).isEqualTo(1);
        invariants.assertHolds(start);
    }
}
