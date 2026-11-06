package com.rafif.wallet.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.outbox.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes transaction events from Kafka. Delivery is at-least-once, so this must be safe to run twice for the
 * same event: {@link NotificationLog} ignores an event id it has already seen.
 * <p>
 * Offsets are committed by Spring only after this method returns. If it throws (for example the database is down),
 * the error handler in {@link NotificationConfig} retries the same message, so nothing is skipped.
 */
@Component
class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationLog notificationLog;

    NotificationListener(ObjectMapper objectMapper, NotificationLog notificationLog) {
        this.objectMapper = objectMapper;
        this.notificationLog = notificationLog;
    }

    @KafkaListener(
            topics = "${wallet.outbox.kafka.topic}",
            autoStartup = "${wallet.notification.consumer.enabled:true}")
    void onMessage(String payload) {
        TransactionEvent event;
        try {
            event = objectMapper.readValue(payload, TransactionEvent.class);
        } catch (JsonProcessingException e) {
            // Retrying cannot fix a message we cannot read, and it would block every message behind it.
            // Skip it loudly. (A real system would park it in a dead-letter topic.)
            log.error("Skipping unreadable event: {}", payload, e);
            return;
        }
        if (notificationLog.record(event)) {
            log.info("Notified: {} {} amount={}", event.type(), event.transactionId(), event.amount());
        } else {
            log.info("Duplicate delivery ignored: event {}", event.eventId());
        }
    }
}
