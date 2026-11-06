package com.rafif.wallet.outbox;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends unpublished outbox events, oldest first, and marks each one as published.
 * <p>
 * <b>At-least-once.</b> An event is marked published only after the sender confirmed it. If the application dies
 * after the message was accepted but before the mark is committed, the next run sends it again. So a message can be
 * delivered twice, but never zero times. Consumers must therefore ignore duplicates (they use the event id).
 * <p>
 * <b>Order.</b> Events are sent in id order, and a failure stops the run: later events must not overtake the failed
 * one. Only one publisher runs at a time across ALL application instances (a database advisory lock), because two
 * publishers working on different rows at once could send events of the same wallet out of order.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final EventSender sender;
    private final OutboxProperties properties;
    private final Clock clock;

    OutboxPublisher(OutboxRepository outbox, EventSender sender, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one batch. Everything happens in one database transaction, which also holds the advisory lock: the lock
     * disappears by itself when the transaction ends, even if the application crashes.
     *
     * @return how many events were sent and marked published
     */
    @Transactional
    public int publishBatch() {
        if (!outbox.tryAcquirePublisherLock()) {
            return 0; // another instance is publishing right now; it will handle these events
        }
        List<OutboxEvent> batch = outbox.findOldestUnpublished(Pageable.ofSize(properties.batchSize()));
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                sender.send(event.getPartitionKey(), event.getPayload());
            } catch (RuntimeException e) {
                // Stop here. Events before this one stay marked (the transaction still commits); this one and the
                // rest are tried again on the next run, in the same order.
                log.warn("could not send outbox event {} (will retry): {}", event.getEventId(), e.toString());
                break;
            }
            event.markPublished(clock.instant());
            sent++;
        }
        return sent;
    }
}
