package com.rafif.wallet.auth;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is making the request. The id comes ONLY from the verified token, never from the request body or URL, so a
 * caller cannot claim to be someone else. (The decoder already rejected any token whose subject is not a user id.)
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
