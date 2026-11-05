package com.rafif.wallet.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String currency;

    /** Minor units (IDR has no decimals). Never negative - also enforced by a DB CHECK constraint. */
    @Column(nullable = false)
    private long balance;

    /** Wrapper type on purpose: null means "new", so Spring Data calls persist() instead of merge(). */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Wallet() {
        // for JPA
    }

    public static Wallet create(String ownerId) {
        Wallet w = new Wallet();
        w.id = UUID.randomUUID();
        w.ownerId = ownerId;
        w.currency = "IDR";
        w.balance = 0L;
        w.createdAt = Instant.now();
        return w;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
