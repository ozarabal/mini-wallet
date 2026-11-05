package com.rafif.wallet.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Always lowercase, so "Alice" and "alice" are the same user. */
    @Column(nullable = false)
    private String username;

    /** A BCrypt hash. The password itself is never stored. Never put this in a response or a log. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    public static User create(String username, String passwordHash) {
        User u = new User();
        u.username = username;
        u.passwordHash = passwordHash;
        u.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // PostgreSQL precision
        return u;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
