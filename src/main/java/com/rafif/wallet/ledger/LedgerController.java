package com.rafif.wallet.ledger;

import com.rafif.wallet.auth.CurrentUser;
import com.rafif.wallet.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: take the caller from the token, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/wallets/{walletId}/transactions")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerQueryService service;

    public LedgerController(LedgerQueryService service) {
        this.service = service;
    }

    /** Ledger history of one of your wallets, newest first. Page numbers start at 0; size is capped at 100. */
    @GetMapping
    @Operation(summary = "Ledger history of one of your wallets, newest first")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (bad page or size)")
    @ApiResponse(responseCode = "403", description = "NOT_WALLET_OWNER")
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND")
    public PageResponse<LedgerEntryResponse> history(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(CurrentUser.id(jwt), walletId, page, size);
    }
}
