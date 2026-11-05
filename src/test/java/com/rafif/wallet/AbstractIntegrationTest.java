package com.rafif.wallet;

import com.rafif.wallet.auth.JwtService;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.support.TestUsers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests. One real PostgreSQL container is shared by all test classes
 * (started once per JVM), so tests exercise real locking, constraints and isolation - no H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /**
     * The signing key used by the app under test. Tests that mint their own tokens need the same key.
     * It is 64+ bytes so tests can also sign with HS384/HS512 (to prove those are rejected).
     */
    public static final String TEST_JWT_SECRET =
            "test-only-secret-test-only-secret-test-only-secret-test-only-secret-1234";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wallet.jwt.secret", () -> TEST_JWT_SECRET);
    }

    // private: subclasses have their own jdbc fields and must not clash with these
    @Autowired
    private JdbcTemplate baseJdbc;
    @Autowired
    private JwtService baseJwtService;

    /** A fresh user with a valid token, for calling the protected API. */
    protected TestUser newUser() {
        return new TestUsers(baseJdbc, baseJwtService).create();
    }
}
