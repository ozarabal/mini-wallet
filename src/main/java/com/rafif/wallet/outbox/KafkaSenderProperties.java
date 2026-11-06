package com.rafif.wallet.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where and how events are sent (prefix {@code wallet.outbox.kafka}). The broker address itself is Spring Boot's
 * standard {@code spring.kafka.bootstrap-servers}.
 *
 * @param topic       the topic every transaction event goes to
 * @param sendTimeout how long to wait for the broker's acknowledgement before giving up; the publisher holds a
 *                    database transaction open meanwhile, so this must stay short
 */
@ConfigurationProperties(prefix = "wallet.outbox.kafka")
public record KafkaSenderProperties(
        @DefaultValue("wallet.transactions") String topic,
        @DefaultValue("10s") Duration sendTimeout) {

    public KafkaSenderProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("wallet.outbox.kafka.topic must not be blank");
        }
        if (sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("wallet.outbox.kafka.send-timeout must be positive");
        }
    }
}
