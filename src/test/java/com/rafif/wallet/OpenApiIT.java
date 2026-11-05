package com.rafif.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;

// @SpringBootTest switches metric exporters off by default; this turns them back on so /actuator/prometheus exists.
@AutoConfigureObservability
class OpenApiIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void apiDocsDescribeAllEndpoints() {
        JsonNode docs = rest.getForObject("/v3/api-docs", JsonNode.class);

        JsonNode paths = docs.get("paths");
        assertThat(paths.has("/api/v1/wallets")).isTrue();
        assertThat(paths.has("/api/v1/wallets/{id}")).isTrue();
        assertThat(paths.has("/api/v1/wallets/{walletId}/topups")).isTrue();
        assertThat(paths.has("/api/v1/transfers")).isTrue();
        assertThat(paths.has("/api/v1/wallets/{walletId}/transactions")).isTrue();
        assertThat(docs.get("info").get("title").asText()).isEqualTo("Mini Wallet API");
    }

    @Test
    void docsDeclareBearerAuthenticationAndMarkOnlyTheAuthEndpointsAsPublic() {
        JsonNode docs = rest.getForObject("/v3/api-docs", JsonNode.class);

        JsonNode scheme = docs.get("components").get("securitySchemes").get("bearerAuth");
        assertThat(scheme.get("type").asText()).isEqualTo("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat").asText()).isEqualTo("JWT");

        // protected by default: the bearer scheme is required at the document root, and an operation inherits it
        // unless it says otherwise
        assertThat(docs.get("security").get(0).has("bearerAuth")).isTrue();
        assertThat(docs.get("paths").get("/api/v1/transfers").get("post").has("security")).isFalse();

        // login and register are public: an explicitly empty requirement
        assertThat(docs.get("paths").get("/api/v1/auth/login").get("post").get("security")).isEmpty();
        assertThat(docs.get("paths").get("/api/v1/auth/register").get("post").get("security")).isEmpty();
    }

    @Test
    void healthAndPrometheusEndpointsAreExposed() {
        assertThat(rest.getForObject("/actuator/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
        assertThat(rest.getForObject("/actuator/prometheus", String.class)).contains("jvm_memory_used_bytes");
    }
}
