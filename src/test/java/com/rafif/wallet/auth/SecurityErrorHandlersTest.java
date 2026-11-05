package com.rafif.wallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** Plain unit test of the 401/403 writers, including the 403 path that no endpoint produces yet. */
class SecurityErrorHandlersTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final SecurityErrorHandlers handlers = new SecurityErrorHandlers(mapper);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallets/abc");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private JsonNode body() throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    @Test
    void accessDeniedBecomesA403ProblemDocument() throws Exception {
        handlers.handle(request, response, new AccessDeniedException("internal detail that must not leak"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body().get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body().get("instance").asText()).isEqualTo("/api/v1/wallets/abc");
        assertThat(response.getContentAsString()).doesNotContain("internal detail");
    }

    @Test
    void missingCredentialsBecomeUnauthenticated() throws Exception {
        handlers.commence(request, response, new InsufficientAuthenticationException("no credentials"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(body().get("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aRejectedTokenBecomesTokenInvalidWithoutRevealingTheReason() throws Exception {
        handlers.commence(request, response, new InvalidBearerTokenException("signature mismatch, secret was abc"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(body().get("code").asText()).isEqualTo("TOKEN_INVALID");
        assertThat(response.getContentAsString()).doesNotContain("signature mismatch").doesNotContain("secret");
    }
}
