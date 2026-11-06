package com.rafif.wallet.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.TransferTestSupport;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A tiny budget (3 requests) that refills so slowly (one token per 1,000 seconds) that it never refills during a
 * test. That makes every assertion exact instead of timing-dependent.
 * (Different properties mean Spring starts a separate application context for this class.)
 */
@TestPropertySource(properties = {
        "wallet.rate-limit.transfers.burst=3",
        "wallet.rate-limit.transfers.refill-per-second=0.001"})
class RateLimitIT extends AbstractIntegrationTest {

    private static final int BURST = 3;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    TransferTestSupport db;
    TestUser user;
    UUID from;
    UUID to;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        user = newUser();
        from = db.wallet(user.id().toString(), 1_000_000);
        to = db.wallet(user.id().toString(), 0);
    }

    private ResponseEntity<Map> transfer(TestUser caller, String key, long amount) {
        HttpHeaders headers = caller.headers();
        headers.set("Idempotency-Key", key);
        return rest.postForEntity("/api/v1/transfers",
                new HttpEntity<>(Map.of("fromWalletId", from, "toWalletId", to, "amount", amount), headers), Map.class);
    }

    private ResponseEntity<Map> transfer(String key, long amount) {
        return transfer(user, key, amount);
    }

    @Test
    void allowsTheBurstThenAnswers429WithRetryAfter() {
        for (int i = 0; i < BURST; i++) {
            assertThat(transfer(UUID.randomUUID().toString(), 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> refused = transfer(UUID.randomUUID().toString(), 10);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(refused.getBody()).containsEntry("code", "RATE_LIMITED").containsEntry("status", 429);
        assertThat(refused.getBody().get("instance")).isEqualTo("/api/v1/transfers");
        String retryAfter = refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    @Test
    void aRefusedRequestChangesNothing() {
        for (int i = 0; i < BURST; i++) {
            transfer(UUID.randomUUID().toString(), 10);
        }
        long transactionsBefore = jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class);

        String key = UUID.randomUUID().toString();
        assertThat(transfer(key, 10).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(db.balance(from)).isEqualTo(1_000_000 - 10L * BURST);
        assertThat(db.balance(to)).isEqualTo(10L * BURST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class)).isEqualTo(transactionsBefore);
        // and the key was not consumed: it has simply never been used
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE idempotency_key = ?", Long.class, key))
                .isZero();
    }

    @Test
    void theLimitIsPerUser() {
        for (int i = 0; i < BURST; i++) {
            transfer(UUID.randomUUID().toString(), 10);
        }
        assertThat(transfer(UUID.randomUUID().toString(), 10).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // another user has their own budget and their own wallets
        TestUser other = newUser();
        UUID otherFrom = db.wallet(other.id().toString(), 500);
        UUID otherTo = db.wallet(other.id().toString(), 0);
        HttpHeaders headers = other.headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/transfers", new HttpEntity<>(
                Map.of("fromWalletId", otherFrom, "toWalletId", otherTo, "amount", 5), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void retriesCountTooBecauseTheyStillCostTheServerWork() {
        String key = UUID.randomUUID().toString();
        assertThat(transfer(key, 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transfer(key, 10).getStatusCode()).isEqualTo(HttpStatus.OK);  // replay
        assertThat(transfer(key, 10).getStatusCode()).isEqualTo(HttpStatus.OK);  // replay

        assertThat(transfer(key, 10).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(db.balance(to)).isEqualTo(10); // the money moved once, as always
    }

    @Test
    void onlyTransfersAreLimited() {
        for (int i = 0; i < BURST + 5; i++) {
            HttpHeaders headers = user.headers();
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
            ResponseEntity<Map> topUp = rest.postForEntity("/api/v1/wallets/" + to + "/topups",
                    new HttpEntity<>(Map.of("amount", 1), headers), Map.class);
            assertThat(topUp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(user.get(rest, "/api/v1/wallets/" + to, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestsWithoutAValidLoginAreAnswered401NotCounted() {
        for (int i = 0; i < BURST + 5; i++) {
            ResponseEntity<Map> noToken = rest.postForEntity("/api/v1/transfers",
                    new HttpEntity<>(Map.of("amount", 1), new HttpHeaders()), Map.class);
            assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // the real user still has the whole budget
        assertThat(transfer(UUID.randomUUID().toString(), 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aHundredSimultaneousRequestsFromOneUserGetExactlyTheBurst() throws Exception {
        List<Callable<HttpStatus>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = UUID.randomUUID().toString();
            tasks.add(() -> HttpStatus.valueOf(transfer(key, 10).getStatusCode().value()));
        }

        List<HttpStatus> statuses = ConcurrentRunner.runAll(tasks);

        assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count()).isEqualTo(BURST);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.TOO_MANY_REQUESTS).count()).isEqualTo(100 - BURST);
        assertThat(db.balance(to)).isEqualTo(10L * BURST); // only the admitted requests moved money
    }
}
