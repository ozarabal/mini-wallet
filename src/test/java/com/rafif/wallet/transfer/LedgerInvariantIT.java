package com.rafif.wallet.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.LedgerInvariants;
import com.rafif.wallet.wallet.CreateWalletRequest;
import com.rafif.wallet.wallet.WalletService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The strongest test in the project: a pile of random, overlapping operations, then the books must balance.
 * <p>
 * The plan (which operations, in which order) is generated from a seed, so a failure can be replayed with
 * {@code ./mvnw verify -Dinvariant.seed=<seed>}. The interleaving of threads is still up to the scheduler;
 * that is the point - the invariants must hold for every interleaving.
 */
class LedgerInvariantIT extends AbstractIntegrationTest {

    private static final int WALLETS = 10;
    private static final int THREADS = 16;
    private static final int OPERATIONS = 1_000;
    private static final long FUNDING = 10_000;
    private static final long TOO_BIG = 5_000_000; // more than all the money that can reach the wallets

    enum Kind { TOP_UP, TRANSFER }

    enum Outcome { APPLIED, REPLAYED, INSUFFICIENT_FUNDS, SELF_TRANSFER, KEY_REUSED }

    /** One planned request. A retry is a second Op with the same key. */
    record Op(Kind kind, UUID from, UUID to, long amount, String key) {
        Op withAmount(long newAmount) {
            return new Op(kind, from, to, newAmount, key);
        }
    }

    @Autowired
    WalletService walletService;
    @Autowired
    TopUpService topUpService;
    @Autowired
    TransferService transferService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void randomConcurrentOperationsKeepTheBooksBalanced() throws Exception {
        long seed = Long.getLong("invariant.seed", 20260401L);
        System.out.println("LedgerInvariantIT seed = " + seed + " (replay with -Dinvariant.seed=" + seed + ")");
        String runId = UUID.randomUUID().toString().substring(0, 8); // keeps keys and owners unique per run

        // --- setup: wallets start empty, and the snapshot is taken BEFORE funding so funding is checked too
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(walletService.create(new CreateWalletRequest("inv-" + runId + "-" + i)).id());
        }
        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        List<UUID> everyone = new ArrayList<>(wallets);
        everyone.addAll(SystemWallets.CASH_IN_IDS); // top-ups come from these, so the wallet set must include all
        var start = invariants.snapshot(everyone.toArray(UUID[]::new));
        for (int i = 0; i < WALLETS; i++) {
            topUpService.topUp("fund-" + runId + "-" + i, wallets.get(i), FUNDING);
        }

        // --- the plan: generated entirely from the seed
        List<Op> plan = plan(new Random(seed), wallets, runId);

        // --- run it on a busy "server": 16 threads working through 1,000 requests
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (Op op : plan) {
            tasks.add(() -> execute(op));
        }
        List<Outcome> outcomes = ConcurrentRunner.runWithThreads(THREADS, tasks);

        // --- the test must not be vacuous: every kind of situation really happened
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        outcomes.forEach(o -> counts.merge(o, 1, Integer::sum));
        System.out.println("LedgerInvariantIT outcomes = " + counts);
        assertThat(counts.getOrDefault(Outcome.APPLIED, 0)).as("applied").isPositive();
        assertThat(counts.getOrDefault(Outcome.REPLAYED, 0)).as("replayed").isPositive();
        assertThat(counts.getOrDefault(Outcome.INSUFFICIENT_FUNDS, 0)).as("insufficient funds").isPositive();
        assertThat(counts.getOrDefault(Outcome.SELF_TRANSFER, 0)).as("self transfers").isPositive();

        // --- the actual proof
        assertThat(invariants.check(start)).as("invariant violations (seed " + seed + ")").isEmpty();
    }

    private List<Op> plan(Random random, List<UUID> wallets, String runId) {
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < OPERATIONS; i++) {
            int roll = random.nextInt(100);
            if (roll < 15 && !ops.isEmpty()) {
                // A retry of an earlier request. Half the time of a very recent one, so the original and its
                // retry are likely to be in flight together; otherwise of any earlier one.
                int window = random.nextBoolean() ? Math.min(ops.size(), 20) : ops.size();
                Op original = ops.get(ops.size() - 1 - random.nextInt(window));
                boolean sameKeyDifferentBody = random.nextInt(10) == 0;
                ops.add(sameKeyDifferentBody ? original.withAmount(original.amount() + 1) : original);
            } else if (roll < 35) {
                ops.add(new Op(Kind.TOP_UP, null, pick(random, wallets), 1 + random.nextInt(5_000), key(runId, i)));
            } else {
                UUID from = pick(random, wallets);
                UUID to = random.nextInt(10) == 0 ? from : pick(random, wallets); // ~10% accidental self-transfers
                long amount = random.nextInt(10) == 0 ? TOO_BIG : 1 + random.nextInt(3_000);
                ops.add(new Op(Kind.TRANSFER, from, to, amount, key(runId, i)));
            }
        }
        return ops;
    }

    private static UUID pick(Random random, List<UUID> wallets) {
        return wallets.get(random.nextInt(wallets.size()));
    }

    private static String key(String runId, int index) {
        return "op-" + runId + "-" + index;
    }

    /** Runs one request. Business refusals are expected outcomes; any other exception fails the test. */
    private Outcome execute(Op op) {
        try {
            TransactionResult result = op.kind() == Kind.TOP_UP
                    ? topUpService.topUp(op.key(), op.to(), op.amount())
                    : transferService.transfer(op.key(), op.from(), op.to(), op.amount());
            return result.replayed() ? Outcome.REPLAYED : Outcome.APPLIED;
        } catch (ApiException e) {
            return switch (e.code()) {
                case INSUFFICIENT_FUNDS -> Outcome.INSUFFICIENT_FUNDS;
                case SELF_TRANSFER -> Outcome.SELF_TRANSFER;
                case IDEMPOTENCY_KEY_REUSED -> Outcome.KEY_REUSED;
                default -> throw e;
            };
        }
    }
}
