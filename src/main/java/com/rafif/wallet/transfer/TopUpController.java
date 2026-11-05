package com.rafif.wallet.transfer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: validate input, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/wallets/{walletId}/topups")
@Tag(name = "Transfers")
public class TopUpController {

    private final TopUpService service;

    public TopUpController(TopUpService service) {
        this.service = service;
    }

    /** 201 the first time, 200 with the same body when the request is a retry of an applied one. */
    @PostMapping
    @Operation(summary = "Top up a wallet",
            description = "The money comes from the system cash-in wallet. Requires an Idempotency-Key. "
                    + "201 when applied, 200 when the same request is retried.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or IDEMPOTENCY_KEY_MISSING")
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND")
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED")
    public ResponseEntity<TransactionResponse> topUp(
            @PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody TopUpRequest request) {
        TransactionResult result = service.topUp(idempotencyKey, walletId, request.amount());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.transaction());
    }
}
