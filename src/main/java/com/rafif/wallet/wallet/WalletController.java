package com.rafif.wallet.wallet;

import com.rafif.wallet.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: take the caller from the token, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets")
public class WalletController {

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a wallet owned by the logged-in user",
            description = "No request body: the owner is always the user in the access token. The balance starts at 0.")
    public WalletResponse create(@AuthenticationPrincipal Jwt jwt) {
        return service.create(CurrentUser.id(jwt));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of your wallets and its current balance")
    @ApiResponse(responseCode = "403", description = "NOT_WALLET_OWNER")
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND")
    public WalletResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(CurrentUser.id(jwt), id);
    }
}
