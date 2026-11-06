package com.rafif.wallet.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.outbox.EventSender;
import com.rafif.wallet.outbox.OutboxEventType;
import com.rafif.wallet.outbox.OutboxPublisher;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import com.rafif.wallet.outbox.TransactionEvent;

/**
 * The consumer against a REAL broker and a real database. The question is always the same: whatever Kafka does
 * (delivers twice, delivers while the database is down, delivers garbage), what ends up in notification_log?
 */
@DirtiesContext // owns a running Kafka listener; do not leave it running for other test classes
class NotificationConsumerIT extends AbstractIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final String TOPIC = "notif-" + UUID.randomUUID();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("wallet.outbox.kafka.topic", () -> TOPIC);
        registry.add("spring.kafka.consumer.group-id", () -> "test-group-" + UUID.randomUUID());
        registry.add("wallet.notification.consumer.enabled", () -> "true");
    }

    @Autowired
    EventSender sender;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    TransferService transferService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL");
    }

    private TransactionEvent newEvent() {
        return new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED,
                UUID.randomUUID(), UUID.randomUUID(), 500, Instant.now());
    }

    private void send(TransactionEvent event) throws Exception {
        sender.send(event.fromWalletId().toString(), objectMapper.writeValueAsString(event));
    }

    private long rowsFor(TransactionEvent event) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE event_id = ?", Long.class, event.eventId());
    }

    @Test
    void anEventFromTheOutboxEndsUpInTheNotificationLog() {
        TransferTestSupport db = new TransferTestSupport(jdbc);
        TestUser user = newUser();
        UUID from = db.wallet(user.id().toString(), 10_000);
        UUID to = db.wallet(user.id().toString(), 0);
        UUID transactionId = transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 250)
                .transaction().transactionId();

        publisher.publishBatch();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT amount FROM notification_log WHERE transaction_id = ?", Long.class, transactionId))
                        .isEqualTo(250));
    }

    @Test
    void theSameEventDeliveredTwiceIsProcessedOnce() throws Exception {
        TransactionEvent duplicate = newEvent();
        TransactionEvent marker = newEvent(); // sent after, same key => handled after the duplicates

        send(duplicate);
        send(duplicate);
        send(duplicate);
        sender.send(duplicate.fromWalletId().toString(), objectMapper.writeValueAsString(marker));

        await().atMost(Duration.ofSeconds(30)).until(() -> rowsFor(marker) == 1);
        assertThat(rowsFor(duplicate)).isEqualTo(1); // three deliveries, one row
    }

    @Test
    void anUnreadableMessageIsSkippedAndDoesNotBlockTheOnesBehindIt() throws Exception {
        TransactionEvent after = newEvent();
        sender.send(after.fromWalletId().toString(), "this is not json");
        send(after);

        await().atMost(Duration.ofSeconds(30)).until(() -> rowsFor(after) == 1);
    }

    @Test
    void whenTheDatabaseFailsTheMessageIsRetriedAndNotLost() throws Exception {
        TransactionEvent event = newEvent();
        jdbc.execute("ALTER TABLE notification_log RENAME TO notification_log_away"); // the "outage"
        try {
            send(event);
            Thread.sleep(3_000); // several retries happen and fail meanwhile
        } finally {
            jdbc.execute("ALTER TABLE notification_log_away RENAME TO notification_log"); // the database is back
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> rowsFor(event) == 1);
    }
}
