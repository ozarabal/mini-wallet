package com.rafif.wallet.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY placeholder so the application starts before the Kafka sender exists (it arrives in the next commit and
 * replaces this class). It only logs, so events are marked as published without going anywhere.
 */
@Component
class LoggingEventSender implements EventSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventSender.class);

    @Override
    public void send(String key, String payload) {
        log.info("outbox event (not sent anywhere yet) key={} payload={}", key, payload);
    }
}
