package com.rafif.wallet.auth;

import java.time.Instant;
import java.util.UUID;

/** What the API shows about a user. Deliberately has no password or hash field. */
public record UserResponse(UUID id, String username, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}
