package com.rafif.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The publisher is run by hand here (the background job is off in tests) with a fake sender instead of Kafka.
 * Batch size 5 makes batching visible with a handful of events.
 */
@TestPropertySource(properties = "wallet.outbox.publisher.batch-size=5")
class OutboxPublisherIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeKafka {
        @Bean
        @Primary
        RecordingEventSender recordingEventSender() {
            return new RecordingEventSender();
        }
    }

    @Autowired
    OutboxPublisher publisher;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    RecordingEventSender sender;
    @Autowired
    TransferService transferService;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    TransferTestSupport db;
    TestUser user;
    UUID from;
    UUID to;

    @BeforeEach
    void setUp() {
        // The database is shared with other test classes: start from "everything older is already sent".
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL");
        sender.clear();
        db = new TransferTestSupport(jdbc);
        user = newUser();
        from = db.wallet(user.id().toString(), 1_000_000);
        to = db.wallet(user.id().toString(), 0);
    }

    /** Makes n transfers, one after another. Returns their event ids in the order they happened. */
    private List<UUID> transfers(int n) {
        List<UUID> transactionIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            transactionIds.add(transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 10 + i)
                    .transaction().transactionId());
        }
        return transactionIds;
    }

    private long unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
    }

    private List<UUID> sentTransactionIds() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (RecordingEventSender.Sent s : sender.sent()) {
            ids.add(UUID.fromString(objectMapper.readTree(s.payload()).get("transactionId").asText()));
        }
        return ids;
    }

    @Test
    void sendsEventsInOrderAndMarksThemPublished() throws Exception {
        List<UUID> expected = transfers(3);

        int sent = publisher.publishBatch();

        assertThat(sent).isEqualTo(3);
        assertThat(sentTransactionIds()).containsExactlyElementsOf(expected);
        assertThat(sender.sent()).allMatch(s -> s.key().equals(from.toString()));
        assertThat(unpublished()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL "
                + "AND aggregate_id IN (?, ?, ?)", Long.class, expected.get(0), expected.get(1), expected.get(2)))
                .isEqualTo(3);
    }

    @Test
    void anEventIsNeverSentAgainOnceMarked() {
        transfers(2);
        publisher.publishBatch();
        sender.clear();

        assertThat(publisher.publishBatch()).isZero();

        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void sendsAtMostOneBatchPerRun() {
        transfers(12);

        assertThat(publisher.publishBatch()).isEqualTo(5);
        assertThat(publisher.publishBatch()).isEqualTo(5);
        assertThat(publisher.publishBatch()).isEqualTo(2);
        assertThat(publisher.publishBatch()).isZero();
        assertThat(sender.sent()).hasSize(12);
    }

    @Test
    void aFailureStopsTheRunKeepsEarlierEventsMarkedAndRetriesInOrder() throws Exception {
        List<UUID> expected = transfers(5);
        sender.failAfter(2); // accepts two, then the broker "goes down"

        int sent = publisher.publishBatch();

        assertThat(sent).isEqualTo(2);
        assertThat(sentTransactionIds()).containsExactlyElementsOf(expected.subList(0, 2));
        assertThat(unpublished()).isEqualTo(3); // the failed one and everything after it waits

        sender.recover();
        assertThat(publisher.publishBatch()).isEqualTo(3);
        // overall: every event exactly once, in the original order - the failure did not reorder or lose anything
        assertThat(sentTransactionIds()).containsExactlyElementsOf(expected);
        assertThat(unpublished()).isZero();
    }

    @Test
    void ifTheMarkingIsLostAfterSendingTheEventIsSentAgain() throws Exception {
        // At-least-once, made visible: the message was accepted, but the transaction that marks it is rolled back
        // (as if the application crashed right after the broker said yes). Nothing is lost - but it will be a duplicate.
        List<UUID> expected = transfers(2);

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishBatch();      // joins this transaction
            status.setRollbackOnly();      // "crash": the marks are not saved
        });
        assertThat(sentTransactionIds()).containsExactlyElementsOf(expected);
        assertThat(unpublished()).isEqualTo(2);

        publisher.publishBatch();

        assertThat(sentTransactionIds()).containsExactly(expected.get(0), expected.get(1), expected.get(0), expected.get(1));
        assertThat(unpublished()).isZero();
    }

    @Test
    void anotherPublisherAlreadyRunningMeansThisOneDoesNothing() throws Exception {
        transfers(3);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // "Another instance": holds the publisher lock inside an open transaction.
            Future<?> other = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                assertThat(outbox.tryAcquirePublisherLock()).isTrue();
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(publisher.publishBatch()).isZero();
            assertThat(sender.sent()).isEmpty();
            assertThat(unpublished()).isEqualTo(3);

            release.countDown();
            other.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // lock released with the other transaction: now this one can work
        assertThat(publisher.publishBatch()).isEqualTo(3);
    }

    @Test
    void manyPublishersAtOnceStillSendEachEventOnceAndInOrder() throws Exception {
        List<UUID> expected = transfers(20);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                // A publisher that finds the lock taken gets 0 and tries again, like the scheduler would.
                int total = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (unpublished() > 0 && System.nanoTime() < deadline) {
                    total += publisher.publishBatch();
                }
                return total;
            });
        }
        List<Integer> perPublisher = ConcurrentRunner.runAll(tasks);

        assertThat(perPublisher.stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(sentTransactionIds()).containsExactlyElementsOf(expected); // no duplicate, no reordering
        assertThat(unpublished()).isZero();
    }

    @Test
    void propertiesRejectNonsense() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(true, 0, java.time.Duration.ofSeconds(1)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(true, 10, java.time.Duration.ZERO));
    }
}
