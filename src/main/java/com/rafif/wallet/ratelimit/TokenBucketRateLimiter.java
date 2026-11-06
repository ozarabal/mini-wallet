package com.rafif.wallet.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A token-bucket rate limiter with one bucket per key (here: per user), kept in memory.
 * <p>
 * Each bucket holds up to {@code burst} tokens. A request takes one token; if there is none, it is refused.
 * Tokens come back continuously at {@code refillPerSecond}, so nothing needs a background timer: a bucket is
 * "topped up" lazily, from the time that passed since it was last used.
 * <p>
 * Limits of this design (see docs/DESIGN_DECISIONS.md): the state lives in this JVM, so with several app instances
 * each one has its own buckets and the real limit is multiplied by the number of instances.
 */
public class TokenBucketRateLimiter {

    /** The answer for one request. {@code retryAfterMillis} is 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterMillis) {
    }

    /** How often to throw away buckets nobody uses any more (so memory does not grow with every user ever seen). */
    private static final long SWEEP_EVERY_MILLIS = 60_000;

    private final int burst;
    private final double refillPerMilli;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAt;

    public TokenBucketRateLimiter(int burst, double refillPerSecond, Clock clock) {
        this.burst = burst;
        this.refillPerMilli = refillPerSecond / 1000.0;
        this.clock = clock;
        this.nextSweepAt = new AtomicLong(clock.millis() + SWEEP_EVERY_MILLIS);
    }

    /** Tries to take one token for {@code key}. Safe to call from many threads at once. */
    public Decision tryAcquire(String key) {
        long now = clock.millis();
        sweepIfDue(now);
        // computeIfAbsent is atomic: two threads racing on a new key still end up sharing ONE bucket.
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
        return bucket.tryTake(now);
    }

    /** Number of buckets currently kept; for tests. */
    int bucketCount() {
        return buckets.size();
    }

    private void sweepIfDue(long now) {
        long due = nextSweepAt.get();
        // Only the one thread that wins the compareAndSet does the sweep.
        if (now >= due && nextSweepAt.compareAndSet(due, now + SWEEP_EVERY_MILLIS)) {
            // A full bucket is indistinguishable from a brand-new one, so removing it loses nothing -
            // even if another thread is using it at this very moment.
            buckets.values().removeIf(bucket -> bucket.isFull(now));
        }
    }

    /** One user's tokens. All access goes through synchronized methods: the work inside is a few additions. */
    private final class Bucket {
        private double tokens;
        private long lastRefillAt;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillAt = now;
        }

        synchronized Decision tryTake(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            double missing = 1 - tokens;
            return new Decision(false, (long) Math.ceil(missing / refillPerMilli));
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= burst;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - lastRefillAt); // a clock that steps backwards must not remove tokens
            tokens = Math.min(burst, tokens + elapsed * refillPerMilli);
            lastRefillAt = Math.max(lastRefillAt, now);
        }
    }
}
