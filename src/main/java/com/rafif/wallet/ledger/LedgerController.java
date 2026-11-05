package com.rafif.wallet.ledger;

import com.rafif.wallet.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: validate input, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/wallets/{walletId}/transactions")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerQueryService service;

    public LedgerController(LedgerQueryService service) {
        this.service = service;
    }

    /** Ledger history of one wallet, newest first. Page numbers start at 0; size is capped at 100. */
    @GetMapping
    @Operation(summary = "Ledger history of a wallet, newest first")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (bad page or size)")
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND")
    public PageResponse<LedgerEntryResponse> history(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(walletId, page, size);
    }
}
