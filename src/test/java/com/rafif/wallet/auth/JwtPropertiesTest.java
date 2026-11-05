package com.rafif.wallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Plain unit test: the app must refuse to start with a weak or missing signing secret. */
class JwtPropertiesTest {

    private static final String SECRET_32_BYTES = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsASecretOfExactlyThirtyTwoBytes() {
        JwtProperties properties = new JwtProperties(SECRET_32_BYTES, "mini-wallet", Duration.ofMinutes(15));

        assertThat(properties.secret()).isEqualTo(SECRET_32_BYTES);
    }

    @Test
    void rejectsASecretShorterThanThirtyTwoBytes() {
        assertThatThrownBy(() -> new JwtProperties(SECRET_32_BYTES.substring(1), "mini-wallet", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void rejectsAMissingSecret() {
        assertThatThrownBy(() -> new JwtProperties(null, "mini-wallet", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroOrNegativeLifetime() {
        assertThatThrownBy(() -> new JwtProperties(SECRET_32_BYTES, "mini-wallet", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(SECRET_32_BYTES, "mini-wallet", Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
