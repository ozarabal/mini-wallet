package com.rafif.wallet.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes a problem document straight to the HTTP response. Servlet filters (security, rate limiting) run before Spring
 * MVC, so {@code GlobalExceptionHandler} never sees their errors; they use this to keep the same error shape.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                             ErrorCode code, String detail) throws IOException {
        ProblemDetail problem = Problems.of(code, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(code.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
