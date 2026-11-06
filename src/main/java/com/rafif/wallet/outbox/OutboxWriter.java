package com.rafif.wallet.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records "this happened" in the outbox table.
 * <p>
 * MANDATORY means it refuses to run unless the caller already has a database transaction. That is the whole point:
 * the outbox row must commit or roll back together with the balance change and the ledger entries. If it were its
 * own transaction, a crash between the two could announce money that never moved, or lose the announcement of money
 * that did.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param partitionKey what Kafka will use to keep related events in order (see DESIGN_DECISIONS)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OutboxEventType type, UUID transactionId, UUID fromWalletId, UUID toWalletId, long amount,
                       String partitionKey) {
        UUID eventId = UUID.randomUUID();
        // PostgreSQL keeps microseconds; truncate so the stored time equals the time inside the payload.
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var event = new TransactionEvent(eventId, transactionId, type, fromWalletId, toWalletId, amount, now);
        try {
            // saveAndFlush: a violated constraint (for example a second event for one transaction) surfaces now,
            // inside the transaction, instead of at commit.
            outbox.saveAndFlush(OutboxEvent.create(
                    eventId, transactionId, type, partitionKey, objectMapper.writeValueAsString(event), now));
        } catch (JsonProcessingException e) {
            // Cannot happen for this record, but if it ever did, rolling back the money move is the safe outcome.
            throw new IllegalStateException("Could not serialise outbox event", e);
        }
    }
}
