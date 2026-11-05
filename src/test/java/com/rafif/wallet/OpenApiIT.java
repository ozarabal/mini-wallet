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
    void healthAndPrometheusEndpointsAreExposed() {
        assertThat(rest.getForObject("/actuator/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
        assertThat(rest.getForObject("/actuator/prometheus", String.class)).contains("jvm_memory_used_bytes");
    }
}
