package com.rafif.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.LedgerInvariants;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.TopUpService;
import com.rafif.wallet.transfer.TransactionResult;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox row must exist if and only if the money moved. These tests read the table directly.
 */
class OutboxIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransferService transferService;
    @Autowired
    TopUpService topUpService;
    @Autowired
    OutboxWriter outboxWriter;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    ObjectMapper objectMapper;

    TransferTestSupport db;
    TestUser user;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        user = newUser();
    }

    private List<Map<String, Object>> eventsOfTransaction(UUID transactionId) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ?", transactionId);
    }

    @Test
    void aTransferWritesOneUnpublishedEventDescribingIt() throws Exception {
        UUID from = db.wallet(user.id().toString(), 1_000);
        UUID to = db.wallet(user.id().toString(), 0);

        TransactionResult result = transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 300);

        List<Map<String, Object>> events = eventsOfTransaction(result.transaction().transactionId());
        assertThat(events).hasSize(1);
        Map<String, Object> row = events.get(0);
        assertThat(row.get("type")).isEqualTo("TRANSFER_COMPLETED");
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("partition_key")).isEqualTo(from.toString()); // keyed by the paying wallet

        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertThat(payload.get("eventId").asText()).isEqualTo(row.get("event_id").toString());
        assertThat(payload.get("transactionId").asText()).isEqualTo(result.transaction().transactionId().toString());
        assertThat(payload.get("type").asText()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(payload.get("fromWalletId").asText()).isEqualTo(from.toString());
        assertThat(payload.get("toWalletId").asText()).isEqualTo(to.toString());
        assertThat(payload.get("amount").asLong()).isEqualTo(300);
        assertThat(payload.get("occurredAt").asText()).isNotBlank();
    }

    @Test
    void theMessageContainsNoUserOrOwnerIds() {
        UUID from = db.wallet(user.id().toString(), 1_000);
        UUID to = db.wallet(user.id().toString(), 0);

        TransactionResult result = transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 300);

        String payload = (String) eventsOfTransaction(result.transaction().transactionId()).get(0).get("payload");
        assertThat(payload).doesNotContain(user.id().toString());
        assertThat(objectMapper.valueToTree(payload).asText()).doesNotContainIgnoringCase("owner");
    }

    @Test
    void aTopUpWritesAnEventKeyedByTheReceivingWallet() throws Exception {
        UUID wallet = db.wallet(user.id().toString(), 0);

        TransactionResult result = topUpService.topUp(user.id(), UUID.randomUUID().toString(), wallet, 500);

        Map<String, Object> row = eventsOfTransaction(result.transaction().transactionId()).get(0);
        assertThat(row.get("type")).isEqualTo("TOPUP_COMPLETED");
        assertThat(row.get("partition_key")).isEqualTo(wallet.toString());
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertThat(payload.get("toWalletId").asText()).isEqualTo(wallet.toString());
        assertThat(payload.get("amount").asLong()).isEqualTo(500);
    }

    @Test
    void aReplayDoesNotAnnounceTheMoneyAgain() {
        UUID from = db.wallet(user.id().toString(), 1_000);
        UUID to = db.wallet(user.id().toString(), 0);
        String key = UUID.randomUUID().toString();

        TransactionResult first = transferService.transfer(user.id(), key, from, to, 300);
        TransactionResult replay = transferService.transfer(user.id(), key, from, to, 300);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().transactionId()).isEqualTo(first.transaction().transactionId());
        assertThat(eventsOfTransaction(first.transaction().transactionId())).hasSize(1);
        // nothing at all was announced for this user's wallets beyond the one event
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE partition_key = ?", Long.class,
                from.toString())).isEqualTo(1);
    }

    @Test
    void aRefusedTransferAnnouncesNothing() {
        UUID from = db.wallet(user.id().toString(), 100);
        UUID to = db.wallet(user.id().toString(), 0);
        long eventsBefore = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);

        assertThatThrownBy(() -> transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 999))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(eventsBefore);
    }

    @Test
    void theEventRollsBackWithTheTransactionItIsWrittenIn() {
        // The outbox row shares the caller's database transaction, so rolling that back must remove it too.
        UUID from = db.wallet(user.id().toString(), 1_000);
        UUID to = db.wallet(user.id().toString(), 0);
        UUID transactionId = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, type, status, idempotency_key, request_hash, owner_id) "
                + "VALUES (?, 'TRANSFER', 'COMPLETED', ?, 'h', ?)", transactionId, UUID.randomUUID().toString(),
                user.id().toString());

        transactionTemplate.executeWithoutResult(status -> {
            outboxWriter.record(OutboxEventType.TRANSFER_COMPLETED, transactionId, from, to, 1, from.toString());
            assertThat(eventsOfTransaction(transactionId)).hasSize(1); // visible inside the transaction
            status.setRollbackOnly();
        });

        assertThat(eventsOfTransaction(transactionId)).isEmpty();
    }

    @Test
    void theWriterRefusesToRunWithoutATransaction() {
        UUID wallet = db.wallet(user.id().toString(), 0);

        assertThatThrownBy(() -> outboxWriter.record(
                OutboxEventType.TOPUP_COMPLETED, UUID.randomUUID(), wallet, wallet, 1, wallet.toString()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void theDatabaseRefusesASecondEventForTheSameTransaction() {
        UUID from = db.wallet(user.id().toString(), 1_000);
        UUID to = db.wallet(user.id().toString(), 0);
        TransactionResult result = transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 10);
        UUID transactionId = result.transaction().transactionId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                outboxWriter.record(OutboxEventType.TRANSFER_COMPLETED, transactionId, from, to, 10, from.toString())))
                .hasMessageContaining("uq_outbox_aggregate");
    }

    @Test
    void manyConcurrentTransfersEachProduceExactlyOneEvent() throws Exception {
        UUID from = db.wallet(user.id().toString(), 10_000);
        UUID to = db.wallet(user.id().toString(), 0);
        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        var start = invariants.snapshot(from, to);

        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            tasks.add(() -> transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 10)
                    .transaction().transactionId());
        }
        List<UUID> transactionIds = ConcurrentRunner.runWithThreads(16, tasks);

        for (UUID id : transactionIds) {
            assertThat(eventsOfTransaction(id)).as("events of " + id).hasSize(1);
        }
        // and the order of the events for this wallet is the order the transfers happened (ids grow with commit order
        // because they are handed out while the wallet is locked)
        List<Long> ids = jdbc.queryForList(
                "SELECT o.id FROM outbox_events o JOIN ledger_entries e ON e.transaction_id = o.aggregate_id "
                        + "WHERE e.wallet_id = ? AND e.direction = 'DEBIT' ORDER BY e.created_at, e.id", Long.class, from);
        assertThat(ids).isSorted();
        invariants.assertHolds(start); // includes "every completed transaction has exactly one event"
    }
}
