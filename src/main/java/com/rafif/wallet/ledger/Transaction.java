package com.rafif.wallet.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One business operation (top-up or transfer). Its ledger entries always balance.
 * The unique (owner_id, idempotency_key) constraint in the database is what stops a request
 * being applied twice.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transaction() {
        // for JPA
    }

    public static Transaction start(TransactionType type, String ownerId, String idempotencyKey, String requestHash) {
        Transaction t = new Transaction();
        t.type = type;
        t.status = TransactionStatus.PENDING;
        t.ownerId = ownerId;
        t.idempotencyKey = idempotencyKey;
        t.requestHash = requestHash;
        // PostgreSQL stores microseconds; truncate so the response we return now equals what a replay reads later.
        t.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return t;
    }

    public void markCompleted() {
        this.status = TransactionStatus.COMPLETED;
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
