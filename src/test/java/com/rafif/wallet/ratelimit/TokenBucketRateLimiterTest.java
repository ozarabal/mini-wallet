package com.rafif.wallet.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import com.rafif.wallet.support.ConcurrentRunner;

/** Pure unit tests: no Spring, no sleeping. A fake clock lets the test decide how much time has passed. */
class TokenBucketRateLimiterTest {

    /** A clock that only moves when the test says so. */
    static class FakeClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    FakeClock clock = new FakeClock();

    @Test
    void allowsABurstThenRefuses() {
        var limiter = new TokenBucketRateLimiter(3, 1, clock);

        assertThat(limiter.tryAcquire("u").allowed()).isTrue();
        assertThat(limiter.tryAcquire("u").allowed()).isTrue();
        assertThat(limiter.tryAcquire("u").allowed()).isTrue();
        assertThat(limiter.tryAcquire("u").allowed()).isFalse();
    }

    @Test
    void tokensComeBackOverTime() {
        var limiter = new TokenBucketRateLimiter(2, 1, clock); // one token per second
        limiter.tryAcquire("u");
        limiter.tryAcquire("u");
        assertThat(limiter.tryAcquire("u").allowed()).isFalse();

        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire("u").allowed()).isFalse(); // not a whole token yet

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("u").allowed()).isTrue();  // exactly one second has passed
        assertThat(limiter.tryAcquire("u").allowed()).isFalse(); // and only one token came back
    }

    @Test
    void neverHoldsMoreThanTheBurstEvenAfterALongPause() {
        var limiter = new TokenBucketRateLimiter(2, 1, clock);
        limiter.tryAcquire("u");

        clock.advance(Duration.ofHours(1));

        assertThat(limiter.tryAcquire("u").allowed()).isTrue();
        assertThat(limiter.tryAcquire("u").allowed()).isTrue();
        assertThat(limiter.tryAcquire("u").allowed()).isFalse(); // an hour of waiting is not an hour of saved tokens
    }

    @Test
    void tellsTheCallerHowLongToWait() {
        var limiter = new TokenBucketRateLimiter(1, 2, clock); // two tokens per second = one per 500 ms
        limiter.tryAcquire("u");

        assertThat(limiter.tryAcquire("u").retryAfterMillis()).isEqualTo(500);

        clock.advance(Duration.ofMillis(200));
        assertThat(limiter.tryAcquire("u").retryAfterMillis()).isEqualTo(300);

        clock.advance(Duration.ofMillis(300));
        assertThat(limiter.tryAcquire("u").allowed()).isTrue(); // waiting the advertised time really works
    }

    @Test
    void usersAreIndependent() {
        var limiter = new TokenBucketRateLimiter(1, 1, clock);

        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue(); // alice using up her budget does not affect bob
    }

    @Test
    void aClockThatGoesBackwardsDoesNotTakeTokensAwayOrGiveFreeOnes() {
        var limiter = new TokenBucketRateLimiter(1, 1, clock);
        limiter.tryAcquire("u");

        clock.advance(Duration.ofSeconds(-30));
        assertThat(limiter.tryAcquire("u").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(30)); // back to where we were: still no time has really passed
        assertThat(limiter.tryAcquire("u").allowed()).isFalse();
    }

    @Test
    void forgetsUsersWhoseBucketIsFullAgain() {
        var limiter = new TokenBucketRateLimiter(2, 1, clock);
        limiter.tryAcquire("idle");
        limiter.tryAcquire("busy");
        limiter.tryAcquire("busy");
        assertThat(limiter.bucketCount()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(61)); // past the sweep interval; "idle" has long been full again
        limiter.tryAcquire("busy");            // any call triggers the sweep

        assertThat(limiter.bucketCount()).isEqualTo(1);
        assertThat(limiter.tryAcquire("idle").allowed()).isTrue(); // a forgotten user simply starts with a full bucket
    }

    @Test
    void doesNotForgetAUserWhoHasNotRecoveredYet() {
        var limiter = new TokenBucketRateLimiter(100, 0.01, clock); // 100 tokens, refills very slowly
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("drained");
        }

        clock.advance(Duration.ofSeconds(61));
        limiter.tryAcquire("someone-else");

        assertThat(limiter.tryAcquire("drained").allowed()).isFalse(); // still limited: its state was not thrown away
    }

    @Test
    void manyThreadsOnOneKeyGetExactlyTheBurst() throws Exception {
        int burst = 20;
        var limiter = new TokenBucketRateLimiter(burst, 1, clock); // clock never moves: no refill during the test

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(() -> limiter.tryAcquire("u").allowed());
        }
        List<Boolean> results = ConcurrentRunner.runWithThreads(32, tasks);

        assertThat(results.stream().filter(allowed -> allowed).count()).isEqualTo(burst);
    }

    @Test
    void propertiesRejectNonsense() {
        assertThatThrownBy(() -> new RateLimitProperties(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
