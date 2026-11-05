package com.rafif.wallet.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The password limit is 72 because BCrypt only looks at the first 72 bytes; accepting more would silently ignore
 * the rest.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "[A-Za-z0-9_.-]+",
                message = "may only contain letters, digits, '_', '.' and '-'") String username,
        @NotBlank @Size(min = 8, max = 72) String password) {

    /** The password is a secret: keep it out of logs and error messages even if this record gets printed. */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=***]";
    }
}
