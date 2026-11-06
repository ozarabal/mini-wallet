package com.rafif.wallet.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row of the outbox table: a message waiting to be sent to Kafka (or already sent, if publishedAt is set). */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OutboxEventType type;

    @Column(name = "partition_key", nullable = false, updatable = false)
    private String partitionKey;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    static OutboxEvent create(UUID eventId, UUID aggregateId, OutboxEventType type, String partitionKey,
                              String payload, Instant createdAt) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = eventId;
        e.aggregateId = aggregateId;
        e.type = type;
        e.partitionKey = partitionKey;
        e.payload = payload;
        e.createdAt = createdAt;
        return e;
    }

    void markPublished(Instant when) {
        this.publishedAt = when.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public OutboxEventType getType() {
        return type;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
