package com.rafif.wallet.outbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake Kafka for tests: remembers every message it accepted and can be told to fail. */
public class RecordingEventSender implements EventSender {

    public record Sent(String key, String payload) {
    }

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    /** How many more messages to accept before failing; negative = never fail. */
    private final AtomicInteger acceptBeforeFailing = new AtomicInteger(-1);

    @Override
    public void send(String key, String payload) {
        if (acceptBeforeFailing.get() == 0) {
            throw new IllegalStateException("broker unavailable (simulated)");
        }
        acceptBeforeFailing.updateAndGet(n -> n > 0 ? n - 1 : n);
        sent.add(new Sent(key, payload));
    }

    public List<Sent> sent() {
        synchronized (sent) {
            return new ArrayList<>(sent);
        }
    }

    public void failAfter(int accepted) {
        acceptBeforeFailing.set(accepted);
    }

    public void recover() {
        acceptBeforeFailing.set(-1);
    }

    public void clear() {
        sent.clear();
        recover();
    }
}
