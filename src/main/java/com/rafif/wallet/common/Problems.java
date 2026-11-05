package com.rafif.wallet.common;

import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 7807 problem document for an error code. Used by the exception handler for controller errors and by
 * the security filters for 401/403, so every error in the API has the same shape.
 */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setTitle(code.title());
        pd.setProperty("code", code.name());
        return pd;
    }
}
