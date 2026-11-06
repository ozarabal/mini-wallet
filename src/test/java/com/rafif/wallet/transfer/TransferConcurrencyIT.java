package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency proof for transfers: no overdraft, no deadlock, no lost update. Every test ends by checking the
 * ledger invariants, so "the numbers look right" is backed by "the books balance".
 */
class TransferConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 100;

    @Autowired
    TransferService transferService;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    TransferTestSupport db;
    LedgerInvariants invariants;
    TestUser user;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        invariants = new LedgerInvariants(jdbc);
        user = newUser();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** Transfer through the service; returns true if applied, false if refused for lack of funds. */
    private boolean tryTransfer(UUID from, UUID to, long amount) {
        try {
            transferService.transfer(user.id(), key(), from, to, amount);
            return true;
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS); // the only acceptable failure
            return false;
        }
    }

    @Test
    void hundredConcurrentTransfersOutOfOneWalletNeverOverdraw() throws Exception {
        // 3,050 funds exactly 30 transfers of 100 and leaves 50 behind: 70 of the 100 requests must be refused.
        UUID source = db.wallet(user.id().toString(), 3_050);
        UUID target = db.wallet(user.id().toString(), 0);
        var start = invariants.snapshot(source, target);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> tryTransfer(source, target, 100));
        }
        long applied = ConcurrentRunner.runAll(tasks).stream().filter(ok -> ok).count();

        assertThat(applied).isEqualTo(30);
        assertThat(db.balance(source)).isEqualTo(50); // never negative, never more than allowed out
        assertThat(db.balance(target)).isEqualTo(3_000);
        assertThat(db.ledgerRows(source)).isEqualTo(30);
        invariants.assertHolds(start);
    }

    @Test
    void hundredConcurrentHttpTransfersNeverOverdrawAndNeverReturn5xx() throws Exception {
        UUID source = db.wallet(user.id().toString(), 3_050);
        UUID target = db.wallet(user.id().toString(), 0);
        var start = invariants.snapshot(source, target);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                HttpHeaders headers = user.headers();
                headers.set("Idempotency-Key", key());
                Map<String, Object> body = Map.of("fromWalletId", source, "toWalletId", target, "amount", 100);
                return rest.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), Map.class)
                        .getStatusCode().value();
            });
        }
        List<Integer> statuses = ConcurrentRunner.runAll(tasks);

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(30);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(70); // INSUFFICIENT_FUNDS
        assertThat(statuses).allMatch(s -> s == 201 || s == 422); // in particular: no 500
        assertThat(db.balance(source)).isEqualTo(50);
        invariants.assertHolds(start);
    }

    @Test
    void oppositeTransfersDoNotDeadlockAcrossManyRounds() throws Exception {
        UUID a = db.wallet(user.id().toString(), 100_000);
        UUID b = db.wallet(user.id().toString(), 100_000);
        var start = invariants.snapshot(a, b);

        for (int round = 0; round < 5; round++) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> tryTransfer(a, b, 10)); // A to B
                tasks.add(() -> tryTransfer(b, a, 10)); // B to A, at the same time
            }
            ConcurrentRunner.runAll(tasks); // a deadlock victim error or a timeout fails the test here
        }

        assertThat(db.balance(a)).isEqualTo(100_000); // equal flow in both directions
        assertThat(db.balance(b)).isEqualTo(100_000);
        assertThat(db.ledgerRows(a)).isEqualTo(5 * 2 * THREADS);
        invariants.assertHolds(start);
    }

    /** A to B, B to C, C to A: three wallets whose locks overlap pairwise, which is the classic cycle. */
    @Test
    void circularTransfersAmongThreeWalletsDoNotDeadlock() throws Exception {
        UUID a = db.wallet(user.id().toString(), 50_000);
        UUID b = db.wallet(user.id().toString(), 50_000);
        UUID c = db.wallet(user.id().toString(), 50_000);
        var start = invariants.snapshot(a, b, c);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> tryTransfer(a, b, 7));
            tasks.add(() -> tryTransfer(b, c, 7));
            tasks.add(() -> tryTransfer(c, a, 7));
        }
        ConcurrentRunner.runAll(tasks);

        assertThat(db.balance(a)).isEqualTo(50_000);
        assertThat(db.balance(b)).isEqualTo(50_000);
        assertThat(db.balance(c)).isEqualTo(50_000);
        invariants.assertHolds(start);
    }
}
