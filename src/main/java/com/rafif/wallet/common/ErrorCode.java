package com.rafif.wallet.common;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Clients switch on the code, never on the message text,
 * so messages can change without breaking anyone.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),
    SELF_TRANSFER(HttpStatus.BAD_REQUEST, "Cannot transfer to the same wallet"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    /** No token was sent at all. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    /** A token was sent but is bad: wrong signature, expired, wrong issuer, unsigned, malformed. */
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token is invalid or has expired"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    /** Logged in, but the wallet belongs to someone else (or to the system). */
    NOT_WALLET_OWNER(HttpStatus.FORBIDDEN, "You do not own this wallet"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    /** Other HTTP-level rejections from Spring (wrong method, unsupported media type...). Status comes from Spring. */
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "Request rejected"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was already used with a different request"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
