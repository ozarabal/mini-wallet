package com.rafif.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for access tokens (prefix {@code wallet.jwt}).
 *
 * @param secret the HMAC key used to sign and verify tokens; whoever knows it can mint tokens for anyone
 * @param issuer who issued the token ({@code iss}); tokens from another issuer are rejected
 * @param ttl    how long a token is valid
 */
@ConfigurationProperties(prefix = "wallet.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("mini-wallet") String issuer,
        @DefaultValue("15m") Duration ttl) {

    /** HS256 needs a key of at least 256 bits; a shorter secret is easy to guess and is refused at startup. */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "wallet.jwt.secret (environment variable JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("wallet.jwt.ttl must be positive");
        }
    }
}
