package com.rafif.wallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.support.ConcurrentRunner;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class AuthIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthService authService;
    @Autowired
    JwtDecoder jwtDecoder;

    /** Usernames must be unique in the shared test database. */
    private static String newUsername() {
        return "user-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private ResponseEntity<JsonNode> register(String username, String password) {
        return rest.postForEntity("/api/v1/auth/register", Map.of("username", username, "password", password),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> login(String username, String password) {
        return rest.postForEntity("/api/v1/auth/login", Map.of("username", username, "password", password),
                JsonNode.class);
    }

    @Test
    void registerReturns201AndNeverEchoesAnySecret() {
        String username = newUsername();

        ResponseEntity<JsonNode> response = register(username, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("id").asText()).isNotBlank();
        assertThat(response.getBody().get("username").asText()).isEqualTo(username);
        assertThat(response.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "username", "createdAt"); // no password, no hash
        assertThat(response.getBody().toString()).doesNotContain(PASSWORD);
    }

    @Test
    void passwordIsStoredAsABcryptHashNeverAsPlainText() {
        String username = newUsername();
        register(username, PASSWORD);

        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);

        assertThat(stored).startsWith("$2").hasSize(60).doesNotContain(PASSWORD);
    }

    @Test
    void samePasswordGetsADifferentHashPerUser() {
        String first = newUsername();
        String second = newUsername();
        register(first, PASSWORD);
        register(second, PASSWORD);

        List<String> hashes = jdbc.queryForList(
                "SELECT password_hash FROM users WHERE username IN (?, ?)", String.class, first, second);

        assertThat(hashes).hasSize(2).doesNotHaveDuplicates(); // each hash has its own random salt
    }

    @Test
    void usernamesAreCaseInsensitive() {
        String username = newUsername();
        register(username.toUpperCase(), PASSWORD);

        assertThat(login(username.toLowerCase(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = register(username.toLowerCase(), PASSWORD);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asText()).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void registeringATakenUsernameReturns409() {
        String username = newUsername();
        register(username, PASSWORD);

        ResponseEntity<JsonNode> response = register(username, "another password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void invalidRegistrationInputReturns400() {
        record Bad(String username, String password) {
        }
        List<Bad> cases = List.of(
                new Bad("ab", PASSWORD),                       // username too short
                new Bad("has space", PASSWORD),                // character not allowed
                new Bad(newUsername(), "short"),               // password too short
                new Bad(newUsername(), "x".repeat(73)),        // password too long for BCrypt
                new Bad(newUsername(), "é".repeat(40)),        // 40 characters but 80 bytes: also too long for BCrypt
                new Bad(" ", PASSWORD));                       // blank username

        for (Bad bad : cases) {
            ResponseEntity<JsonNode> response = register(bad.username(), bad.password());

            assertThat(response.getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void loginReturnsATokenWhoseClaimsAreRight() {
        String username = newUsername();
        UUID userId = UUID.fromString(register(username, PASSWORD).getBody().get("id").asText());

        ResponseEntity<JsonNode> response = login(username, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.getBody().get("expiresInSeconds").asLong()).isEqualTo(900);

        Jwt jwt = jwtDecoder.decode(response.getBody().get("accessToken").asText()); // checks signature and expiry
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("mini-wallet");
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
    }

    @Test
    void tokenIsSignedWithHs256() throws Exception {
        String username = newUsername();
        register(username, PASSWORD);

        String token = login(username, PASSWORD).getBody().get("accessToken").asText();

        String headerJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertThat(new ObjectMapper().readTree(headerJson).get("alg").asText()).isEqualTo("HS256");
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        String username = newUsername();
        register(username, PASSWORD);

        ResponseEntity<JsonNode> wrongPassword = login(username, "not the password");
        ResponseEntity<JsonNode> unknownUser = login(newUsername(), "not the password");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        // identical body apart from the request path: nothing tells an attacker which usernames exist
        assertThat(unknownUser.getBody().get("code")).isEqualTo(wrongPassword.getBody().get("code"));
        assertThat(unknownUser.getBody().get("title")).isEqualTo(wrongPassword.getBody().get("title"));
        assertThat(unknownUser.getBody().get("detail")).isEqualTo(wrongPassword.getBody().get("detail"));
    }

    @Test
    void aPasswordTooLongToExistIsJustInvalidCredentials() {
        String username = newUsername();
        register(username, PASSWORD);

        ResponseEntity<JsonNode> response = login(username, "x".repeat(200));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithMissingFieldsReturns400() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/auth/login", Map.of("username", "x"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Twenty people registering the same new name at the same moment: exactly one wins. Hashing a password takes
     * ~100 ms, so many of them pass the "is it taken?" check before anyone has saved; the database unique constraint
     * has to decide, and the losers must get a clean 409 (USERNAME_TAKEN), not an error.
     */
    @Test
    void concurrentRegistrationsOfTheSameUsernameCreateExactlyOneUser() throws Exception {
        String username = newUsername();

        List<Boolean> created = ConcurrentRunner.runRepeated(20, () -> {
            try {
                authService.register(new RegisterRequest(username, PASSWORD));
                return true;
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.USERNAME_TAKEN); // the only acceptable failure
                return false;
            }
        });

        assertThat(created.stream().filter(ok -> ok)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username))
                .isEqualTo(1);
    }
}
