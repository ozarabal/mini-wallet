package com.rafif.wallet.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends one event to Kafka and WAITS for the broker's acknowledgement before returning.
 * <p>
 * Waiting is what makes the outbox safe: the publisher marks an event as published only when this method returns, so
 * returning before the broker confirmed would let an event be marked as sent and still be lost.
 * The producer is configured with {@code acks=all} (every in-sync replica has the message) and idempotence on, so
 * the client's own internal retries cannot create duplicates or reorder messages of one key.
 */
@Component
class KafkaEventSender implements EventSender {

    private final KafkaTemplate<String, String> kafka;
    private final KafkaSenderProperties properties;

    KafkaEventSender(KafkaTemplate<String, String> kafka, KafkaSenderProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void send(String key, String payload) {
        try {
            kafka.send(properties.topic(), key, payload).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the "please stop" signal for whoever runs this thread
            throw new IllegalStateException("interrupted while sending to Kafka", e);
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            // Not acknowledged: either the future failed or timed out, or send() itself threw because the broker could
            // not even be reached (it blocks up to max.block.ms for metadata, then throws).
            // The publisher leaves the event unpublished and tries again on its next run.
            throw new IllegalStateException("Kafka did not acknowledge the event: " + e, e);
        }
    }
}
