package com.rafif.wallet.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class NotificationConfig {

    /**
     * When the listener throws, retry the SAME message every second, without limit.
     * Spring's default is 10 quick attempts and then to skip the message, which would silently lose an event
     * during a longer database outage. Retrying blocks only this partition, and the outbox keeps the source of truth.
     */
    @Bean
    CommonErrorHandler notificationErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
