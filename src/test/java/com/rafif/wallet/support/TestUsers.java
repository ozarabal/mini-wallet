package com.rafif.wallet.support;

import com.rafif.wallet.auth.JwtService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates users for tests. The row is inserted directly and the token is minted by the real {@link JwtService}, so
 * the token is a genuine one that the app accepts, without paying for BCrypt (about 100 ms) in every test.
 * These users have no usable password, so nobody can log in as them.
 */
public class TestUsers {

    private static final String UNUSABLE_HASH = "$2a$10$" + "x".repeat(53);

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;

    public TestUsers(JdbcTemplate jdbc, JwtService jwtService) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
    }

    public TestUser create() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                id, "test-" + id, UNUSABLE_HASH);
        return new TestUser(id, jwtService.issue(id).accessToken());
    }
}
