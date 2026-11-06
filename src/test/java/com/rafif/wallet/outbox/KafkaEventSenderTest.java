package com.rafif.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The sender's whole job is to return only AFTER the broker acknowledged. A fake KafkaTemplate lets us control
 * exactly when (and whether) the acknowledgement comes, which a real broker cannot do on demand.
 */
class KafkaEventSenderTest {

    @SuppressWarnings("unchecked")
    private KafkaEventSender senderWhoseKafkaReturns(CompletableFuture<SendResult<String, String>> future, Duration timeout) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(future);
        return new KafkaEventSender(kafka, new KafkaSenderProperties("t", timeout));
    }

    @Test
    void doesNotReturnUntilTheBrokerAcknowledges() {
        CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        KafkaEventSender sender = senderWhoseKafkaReturns(ack, Duration.ofSeconds(10));
        CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> ack.complete(null));

        long start = System.nanoTime();
        sender.send("k", "v");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(250));
    }

    @Test
    void failsWhenTheBrokerRejects() {
        CompletableFuture<SendResult<String, String>> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new RuntimeException("not enough replicas"));

        assertThatThrownBy(() -> senderWhoseKafkaReturns(rejected, Duration.ofSeconds(10)).send("k", "v"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsWhenTheAcknowledgementNeverComes() {
        CompletableFuture<SendResult<String, String>> never = new CompletableFuture<>();

        assertThatThrownBy(() -> senderWhoseKafkaReturns(never, Duration.ofMillis(200)).send("k", "v"))
                .isInstanceOf(IllegalStateException.class);
    }
}
