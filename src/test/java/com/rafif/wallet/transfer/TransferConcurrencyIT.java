package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** First concurrency checks for the transfer service. */
class TransferConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;
    @Autowired
    JdbcTemplate jdbc;

    TransferTestSupport db;
    LedgerInvariants invariants;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        invariants = new LedgerInvariants(jdbc);
    }

    @Test
    void concurrentTransfersOutOfOneWalletNeverOverdraw() throws Exception {
        UUID source = db.wallet("conc-source", 1_000);
        UUID target = db.wallet("conc-target", 0);
        var start = invariants.snapshot(source, target);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> {
                try {
                    transferService.transfer(UUID.randomUUID().toString(), source, target, 100);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    return false;
                }
            });
        }
        long succeeded = ConcurrentRunner.runAll(tasks).stream().filter(ok -> ok).count();

        assertThat(succeeded).isEqualTo(10); // 1000 / 100: no more, no fewer
        assertThat(db.balance(source)).isZero();
        assertThat(db.balance(target)).isEqualTo(1_000);
        assertThat(db.ledgerRows(source)).isEqualTo(10);
        invariants.assertHolds(start);
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        UUID a = db.wallet("dead-a", 10_000);
        UUID b = db.wallet("dead-b", 10_000);
        var start = invariants.snapshot(a, b);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                transferService.transfer(UUID.randomUUID().toString(), a, b, 10);
                return true;
            });
            tasks.add(() -> {
                transferService.transfer(UUID.randomUUID().toString(), b, a, 10);
                return true;
            });
        }
        ConcurrentRunner.runAll(tasks); // fails on any deadlock victim error or timeout

        assertThat(db.balance(a)).isEqualTo(10_000); // 20 out, 20 in
        assertThat(db.balance(b)).isEqualTo(10_000);
        invariants.assertHolds(start);
    }
}
