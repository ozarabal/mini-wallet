package com.rafif.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.TopUpService;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Against a REAL Kafka broker (Testcontainers), not a fake: the sender, the publisher and the outbox table together.
 * Each test class gets its own topic, so messages from other tests cannot be mixed in.
 */
class KafkaEventSenderIT extends AbstractIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
            .withEnv("KAFKA_NUM_PARTITIONS", "3"); // topics are auto-created with 3 partitions, as in docker-compose

    static final String TOPIC = "test-" + UUID.randomUUID();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("wallet.outbox.kafka.topic", () -> TOPIC);
    }

    @Autowired
    EventSender sender;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    TransferService transferService;
    @Autowired
    TopUpService topUpService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    TransferTestSupport db;
    TestUser user;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL");
        db = new TransferTestSupport(jdbc);
        user = newUser();
    }

    /** Reads the topic from the beginning until it has seen {@code expected} messages with this marker, or time runs out. */
    private List<ConsumerRecord<String, String>> consume(String marker, int expected) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(marker) || record.key().equals(marker)) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    @Test
    void publishedTransfersArriveInKafkaInOrderKeyedByThePayingWallet() throws Exception {
        UUID from = db.wallet(user.id().toString(), 100_000);
        UUID to = db.wallet(user.id().toString(), 0);
        List<UUID> transactionIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            transactionIds.add(transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 10 + i)
                    .transaction().transactionId());
        }

        assertThat(publisher.publishBatch()).isEqualTo(10);

        List<ConsumerRecord<String, String>> records = consume(from.toString(), 10);
        assertThat(records).hasSize(10);
        assertThat(records).allMatch(r -> r.key().equals(from.toString()));
        assertThat(records.stream().map(ConsumerRecord::partition).distinct()).hasSize(1); // same key, same partition
        List<UUID> received = new ArrayList<>();
        for (ConsumerRecord<String, String> r : records) {
            received.add(UUID.fromString(objectMapper.readTree(r.value()).get("transactionId").asText()));
        }
        assertThat(received).containsExactlyElementsOf(transactionIds); // order preserved
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class))
                .isZero();
    }

    @Test
    void aTopUpArrivesKeyedByTheReceivingWallet() throws Exception {
        UUID wallet = db.wallet(user.id().toString(), 0);
        UUID transactionId = topUpService.topUp(user.id(), UUID.randomUUID().toString(), wallet, 700)
                .transaction().transactionId();

        publisher.publishBatch();

        List<ConsumerRecord<String, String>> records = consume(wallet.toString(), 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(wallet.toString());
        var json = objectMapper.readTree(records.get(0).value());
        assertThat(json.get("type").asText()).isEqualTo("TOPUP_COMPLETED");
        assertThat(json.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(json.get("amount").asLong()).isEqualTo(700);
    }

    @Test
    void differentKeysCanSpreadOverPartitionsButEachKeyStaysOnOne() {
        Set<Integer> partitions = new HashSet<>();
        String marker = "spread-" + UUID.randomUUID();
        for (int i = 0; i < 12; i++) {
            sender.send(marker + "-key" + i, "{\"marker\":\"" + marker + "\"}");
        }

        List<ConsumerRecord<String, String>> records = consume(marker, 12);

        assertThat(records).hasSize(12);
        records.forEach(r -> partitions.add(r.partition()));
        assertThat(partitions.size()).as("12 keys over 3 partitions").isGreaterThan(1);
    }

    @Test
    void whenTheBrokerCannotBeReachedSendingFailsQuicklyInsteadOfHanging() {
        // A sender pointed at a port where nothing listens, with the same bounded timeouts as the real configuration.
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        var factory = new DefaultKafkaProducerFactory<String, String>(config);
        try {
            var broken = new KafkaEventSender(new KafkaTemplate<>(factory),
                    new KafkaSenderProperties(TOPIC, Duration.ofSeconds(5)));
            long start = System.nanoTime();

            assertThatThrownBy(() -> broken.send("k", "v")).isInstanceOf(IllegalStateException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void propertiesRejectNonsense() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new KafkaSenderProperties(" ", Duration.ofSeconds(1)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new KafkaSenderProperties("t", Duration.ZERO));
    }
}
