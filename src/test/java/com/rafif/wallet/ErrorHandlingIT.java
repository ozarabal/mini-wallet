package com.rafif.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.support.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Spring's own HTTP errors must keep their real status instead of turning into 500. */
class ErrorHandlingIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    TestUser user;

    @BeforeEach
    void setUp() {
        user = newUser(); // logged in, so we reach Spring MVC's own 404 / 405 handling
    }

    @Test
    void unknownUrlReturns404NotFound() {
        ResponseEntity<Map> response = user.get(rest, "/api/v1/does-not-exist", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "RESOURCE_NOT_FOUND");
    }

    @Test
    void wrongHttpMethodReturns405() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/wallets/" + UUID.randomUUID(), HttpMethod.DELETE,
                new HttpEntity<>(user.headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("code", "REQUEST_REJECTED");
    }
}
