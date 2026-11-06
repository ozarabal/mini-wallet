package com.rafif.wallet.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Calls the publisher over and over. fixedDelay (not fixedRate) means the next run starts only after the previous one
 * ended, so runs of one instance never overlap. Switch off with {@code wallet.outbox.publisher.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "wallet.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisher publisher;

    OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.publisher.interval:500ms}")
    void run() {
        try {
            // Keep going while there is a full backlog, instead of waiting a whole interval between batches.
            while (publisher.publishBatch() > 0) {
                // loop
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws would be logged and retried anyway; this keeps the message short.
            log.warn("outbox publisher run failed: {}", e.toString());
        }
    }
}
