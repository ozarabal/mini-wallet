package com.rafif.wallet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Security runs in servlet filters, before Spring MVC and our {@code GlobalExceptionHandler}, so its errors have to be
 * written by hand. Writing the same problem-document shape keeps every error in the API consistent.
 * <ul>
 *   <li>401 {@code UNAUTHENTICATED}: no token was sent.</li>
 *   <li>401 {@code TOKEN_INVALID}: a token was sent but it is bad (the reason is deliberately not revealed).</li>
 *   <li>403 {@code FORBIDDEN}: logged in, but not allowed.</li>
 * </ul>
 */
@Component
class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    SecurityErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (exception instanceof OAuth2AuthenticationException) {
            // The bearer-token filter found a token and rejected it.
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            write(request, response, ErrorCode.TOKEN_INVALID, "The access token is invalid or has expired");
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(request, response, ErrorCode.UNAUTHENTICATED,
                    "Authentication is required: send 'Authorization: Bearer <token>'");
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(request, response, ErrorCode.FORBIDDEN, "You are not allowed to do this");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        ProblemResponses.write(objectMapper, request, response, code, detail);
    }
}
