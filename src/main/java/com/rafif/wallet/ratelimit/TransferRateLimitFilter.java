package com.rafif.wallet.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits how fast one user can call {@code POST /api/v1/transfers}.
 * <p>
 * It is placed in the security filter chain right AFTER the token has been checked, because the limit is per user and
 * we only know the user once the token is verified. Requests without a valid login are not counted here (they are
 * rejected with 401 later in the chain), so an anonymous caller cannot use up somebody else's budget.
 * <p>
 * Every request counts, including retries with the same Idempotency-Key and requests that later fail validation: the
 * limiter protects the server's capacity, and a replay still costs a database lookup.
 * <p>
 * This class is deliberately NOT a Spring bean: Spring Boot would also register any Filter bean in the servlet
 * container, where it would run before security (with nobody logged in). {@code SecurityConfig} creates it by hand.
 */
public class TransferRateLimitFilter extends OncePerRequestFilter {

    static final String TRANSFERS_PATH = "/api/v1/transfers";

    private final TokenBucketRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public TransferRateLimitFilter(TokenBucketRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && TRANSFERS_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response); // not logged in: the authorization step will answer 401
            return;
        }

        // The user is the token's subject, the same value the services use as the caller id.
        TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(token.getName());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000); // round up, never "0"
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        ProblemResponses.write(objectMapper, request, response, ErrorCode.RATE_LIMITED,
                "Too many transfer requests. Try again in " + retryAfterSeconds + " second(s).");
    }
}
