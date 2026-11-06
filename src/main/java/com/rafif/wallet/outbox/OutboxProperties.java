package com.rafif.wallet.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the outbox publisher (prefix {@code wallet.outbox.publisher}).
 *
 * @param enabled   turn the background job off (tests do, so they can run the publisher step by step)
 * @param batchSize the most events sent in one run
 * @param interval  pause between the end of one run and the start of the next
 */
@ConfigurationProperties(prefix = "wallet.outbox.publisher")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int batchSize,
        @DefaultValue("500ms") Duration interval) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("wallet.outbox.publisher.batch-size must be at least 1");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("wallet.outbox.publisher.interval must be positive");
        }
    }
}
