package com.leadmanagement.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a unique correlation ID to every request.
 *
 * If the client sends an X-Correlation-ID header, we use that.
 * Otherwise we generate a new UUID.
 *
 * The ID is added to MDC so it appears in every log line for that request,
 * and returned in the response header so clients can correlate logs.
 *
 * Order(1) runs this before the tenant interceptor.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // Always clean up MDC
        }
    }

    /**
     * Returns the current request's correlation ID from MDC.
     * Useful for services that need to pass it to events.
     */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : "";
    }
}

