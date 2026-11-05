package com.rafif.wallet.wallet;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        w.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // PostgreSQL precision
        return w;
    }

    /** Removes money. The balance can never go below zero (the DB CHECK constraint is the second line of defence). */
    public void debit(long amount) {
        requirePositive(amount);
        if (balance < amount) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Wallet " + id + " has insufficient funds");
        }
        balance -= amount;
    }

    public void credit(long amount) {
        requirePositive(amount);
        balance = Math.addExact(balance, amount); // fails loudly on overflow instead of wrapping negative
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
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
