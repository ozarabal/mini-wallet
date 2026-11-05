package com.rafif.wallet.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI walletOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mini Wallet API")
                        .version("v1")
                        .description("""
                                Digital wallet backend: top-up, peer-to-peer transfer and a double-entry ledger.

                                Register and log in under /api/v1/auth to get an access token, then press "Authorize"
                                and paste it. Every other endpoint needs `Authorization: Bearer <token>`.

                                Amounts are `long` values in minor units (IDR has no decimals).
                                Every write endpoint requires an `Idempotency-Key` header: a retry with the same key and
                                body returns the original result (HTTP 200) without applying it twice; the same key with
                                a different body returns 422 `IDEMPOTENCY_KEY_REUSED`.
                                Errors are RFC 7807 problem documents with a stable `code` property."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                // Required by default; the auth endpoints switch it off with @SecurityRequirements.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
