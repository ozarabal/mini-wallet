package com.rafif.wallet.auth;

/** Returned by login. Send {@code accessToken} as {@code Authorization: Bearer <token>}. */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
