package com.rafif.wallet.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limit on {@code POST /api/v1/transfers} per user (prefix {@code wallet.rate-limit.transfers}).
 * Think of a bucket that holds up to {@code burst} tokens: each request takes one, and tokens drip back in at
 * {@code refillPerSecond}. A user can therefore send a burst, then settle to the refill rate.
 *
 * @param burst           the most requests a user can send at once (bucket size)
 * @param refillPerSecond the steady rate a user can keep up (tokens added per second)
 */
@ConfigurationProperties(prefix = "wallet.rate-limit.transfers")
public record RateLimitProperties(
        @DefaultValue("20") int burst,
        @DefaultValue("10") double refillPerSecond) {

    public RateLimitProperties {
        if (burst < 1) {
            throw new IllegalArgumentException("wallet.rate-limit.transfers.burst must be at least 1");
        }
        if (!(refillPerSecond > 0)) { // also rejects NaN
            throw new IllegalArgumentException("wallet.rate-limit.transfers.refill-per-second must be positive");
        }
    }
}
