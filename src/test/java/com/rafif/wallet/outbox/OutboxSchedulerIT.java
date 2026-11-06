package com.rafif.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The background job really runs on its own. It is switched on here only.
 * <p>
 * {@code @DirtiesContext} closes this application context afterwards: a cached context with a running background job
 * would keep marking events as published behind the back of every later test class that shares the database.
 */
@DirtiesContext
@TestPropertySource(properties = {
        "wallet.outbox.publisher.enabled=true",
        "wallet.outbox.publisher.interval=100ms"})
class OutboxSchedulerIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeKafka {
        @Bean
        @Primary
        RecordingEventSender recordingEventSender() {
            return new RecordingEventSender();
        }
    }

    @Autowired
    TransferService transferService;
    @Autowired
    RecordingEventSender sender;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void newEventsAreSentWithoutAnyoneCallingThePublisher() throws Exception {
        TestUser user = newUser();
        TransferTestSupport db = new TransferTestSupport(jdbc);
        UUID from = db.wallet(user.id().toString(), 1_000);
        UUID to = db.wallet(user.id().toString(), 0);

        UUID transactionId = transferService.transfer(user.id(), UUID.randomUUID().toString(), from, to, 50)
                .transaction().transactionId();

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NOT NULL",
                Long.class, transactionId) == 0) {
            assertThat(System.nanoTime()).as("event published within 15 s").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(sender.sent()).anyMatch(s -> s.payload().contains(transactionId.toString()));
    }
}
