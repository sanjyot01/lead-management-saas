package com.leadmanagement.infrastructure.web;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.lead.application.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Enforces the per-tenant lead-ingestion rate limit (Phase 5).
 *
 * Registered in the Spring Security chain AFTER JwtAuthenticationFilter
 * (see SecurityConfig): the tenant budget is keyed off TenantContext, which
 * only exists inside that filter's scope — running anywhere else would mean
 * no tenant, and RateLimitService would silently fail open on every request.
 *
 * Scope: POST /api/v1/leads only (the spec's "lead ingestion" limit). Reads
 * and other endpoints are not metered.
 *
 * On denial: 429 with Retry-After = seconds until the tenant's fixed window
 * resets, so well-behaved clients can back off precisely instead of guessing.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofHours(1);

    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;
    private final int maxRequestsPerHour;

    public RateLimitFilter(RateLimitService rateLimitService,
                           MeterRegistry meterRegistry,
                           @Value("${leadmanagement.rate-limit.max-requests-per-hour:1000}") int maxRequestsPerHour) {
        this.rateLimitService = rateLimitService;
        this.meterRegistry = meterRegistry;
        this.maxRequestsPerHour = maxRequestsPerHour;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // No tenant context means the request is unauthenticated — let the
        // authorization layer reject it; there is no budget to charge yet.
        if (TenantContext.getCurrentTenantId() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (rateLimitService.tryConsume(maxRequestsPerHour, WINDOW)) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = rateLimitService.windowResetSeconds(WINDOW);
        meterRegistry.counter("lead.ratelimit.blocked.total").increment();
        log.warn("Rate limit exceeded for tenant {} — {} req/hour budget exhausted, retry in {}s",
            TenantContext.getCurrentTenantId(), maxRequestsPerHour, retryAfterSeconds);

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write(
            "{\"status\": 429, \"message\": \"Rate limit exceeded: " + maxRequestsPerHour
                + " requests per hour per tenant\", \"retryAfterSeconds\": " + retryAfterSeconds + "}");
    }

    /** Only lead ingestion is metered — everything else passes untouched. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
            && request.getRequestURI().startsWith("/api/v1/leads"));
    }
}
