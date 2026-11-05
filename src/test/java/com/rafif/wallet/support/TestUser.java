package com.rafif.wallet.support;

import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** A user that exists in the database and holds a valid access token, for tests that call the HTTP API. */
public record TestUser(UUID id, String token) {

    /** New headers with this user's token. The caller may add more (for example Idempotency-Key). */
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    public <T> ResponseEntity<T> get(TestRestTemplate rest, String url, Class<T> type) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), type);
    }

    public <T> ResponseEntity<T> post(TestRestTemplate rest, String url, Object body, Class<T> type) {
        return post(rest, url, body, headers(), type);
    }

    public <T> ResponseEntity<T> post(TestRestTemplate rest, String url, Object body, HttpHeaders headers,
                                      Class<T> type) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }
}
