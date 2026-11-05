package com.rafif.wallet.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock is a bean so code that depends on time (token expiry, rate limits) can be tested with a fake one. */
@Configuration
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
