package com.rafif.wallet.outbox;

/**
 * Hands one message to the outside world (Kafka). The publisher only depends on this small interface, so it can be
 * tested without a broker.
 * <p>
 * Contract: return only when the message is safely accepted (for Kafka: acknowledged by the broker); throw if it is
 * not. Returning early would let the publisher mark the event as sent when it might still be lost.
 */
public interface EventSender {

    /**
     * @param key     messages with the same key must stay in order
     * @param payload the JSON message
     */
    void send(String key, String payload);
}
