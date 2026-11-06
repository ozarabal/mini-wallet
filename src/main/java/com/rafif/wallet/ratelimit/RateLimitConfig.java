package com.rafif.wallet.ratelimit;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    /** One limiter for transfers. It takes the application's {@link Clock} so tests can control time. */
    @Bean
    TokenBucketRateLimiter transferRateLimiter(RateLimitProperties properties, Clock clock) {
        return new TokenBucketRateLimiter(properties.burst(), properties.refillPerSecond(), clock);
    }
}
