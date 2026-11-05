package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** First concurrency checks for the transfer service. Milestone 2 extends these into the full proof suite. */
class TransferConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;
    @Autowired
    JdbcTemplate jdbc;

    TransferTestSupport db;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
    }

    /** Starts all tasks at the same moment so they really compete for the same rows. */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS)); // a deadlock would time out here
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTransfersOutOfOneWalletNeverOverdraw() throws Exception {
        UUID source = db.wallet("conc-source", 1_000);
        UUID target = db.wallet("conc-target", 0);

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
        long succeeded = runTogether(tasks).stream().filter(ok -> ok).count();

        assertThat(succeeded).isEqualTo(10); // 1000 / 100: no more, no fewer
        assertThat(db.balance(source)).isZero();
        assertThat(db.balance(target)).isEqualTo(1_000);
        assertThat(db.ledgerRows(source)).isEqualTo(10);
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        UUID a = db.wallet("dead-a", 10_000);
        UUID b = db.wallet("dead-b", 10_000);

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
        runTogether(tasks); // throws on any deadlock victim error or timeout

        assertThat(db.balance(a)).isEqualTo(10_000); // 20 out, 20 in
        assertThat(db.balance(b)).isEqualTo(10_000);
        assertThat(db.balance(a) + db.balance(b)).isEqualTo(20_000);
    }
}
