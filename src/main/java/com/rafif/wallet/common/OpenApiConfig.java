package com.rafif.wallet.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI walletOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Mini Wallet API")
                .version("v1")
                .description("""
                        Digital wallet backend: top-up, peer-to-peer transfer and a double-entry ledger.

                        Amounts are `long` values in minor units (IDR has no decimals).
                        Every write endpoint requires an `Idempotency-Key` header: a retry with the same key and
                        body returns the original result (HTTP 200) without applying it twice; the same key with a
                        different body returns 422 `IDEMPOTENCY_KEY_REUSED`.
                        Errors are RFC 7807 problem documents with a stable `code` property."""));
    }
}
