package com.rafif.wallet.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    /** The password is a secret: keep it out of logs and error messages even if this record gets printed. */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }
}
