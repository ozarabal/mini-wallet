package com.rafif.wallet.transfer;

import com.rafif.wallet.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: validate input, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /** 201 the first time, 200 with the same body when the request is a retry of an applied one. */
    @PostMapping
    @Operation(summary = "Transfer money out of one of your wallets",
            description = "The source wallet must be yours; the destination can be any wallet. "
                    + "Requires an Idempotency-Key. 201 when applied, 200 when the same request is retried.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR, SELF_TRANSFER or IDEMPOTENCY_KEY_MISSING")
    @ApiResponse(responseCode = "403", description = "NOT_WALLET_OWNER (the source wallet is not yours)")
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND")
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_FUNDS or IDEMPOTENCY_KEY_REUSED")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransactionResult result = service.transfer(CurrentUser.id(jwt), idempotencyKey,
                request.fromWalletId(), request.toWalletId(), request.amount());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.transaction());
    }
}
