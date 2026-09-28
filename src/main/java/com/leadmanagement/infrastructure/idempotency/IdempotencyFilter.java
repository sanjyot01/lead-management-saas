package com.leadmanagement.infrastructure.idempotency;

import com.leadmanagement.infrastructure.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Request idempotency via the X-Idempotency-Key header (Phase 5).
 *
 * Reserve-first flow (NOT the naive lookup-then-execute-then-save shape —
 * that has the same race as Phase 4's non-atomic outbox claim, in HTTP form:
 * two concurrent retries both miss the lookup and both create leads):
 *
 * 1. Hash the request (method|uri|body, SHA-256).
 * 2. INSERT the claim row BEFORE processing. The UNIQUE(tenant_id, key)
 *    constraint makes exactly one concurrent caller win.
 * 3. Losers are classified: completed -> replay the cached response;
 *    in flight -> 409 (retry shortly); different body under the same key
 *    -> 422 (client bug, never replay someone else's response).
 * 4. Winner processes normally; the captured response is stored for replay,
 *    UNLESS it must not be replayed (5xx and auth-state-dependent 4xx:
 *    401/403/429) — those release the claim so a retry gets a real attempt.
 *
 * Runs in the security chain after RateLimitFilter (SecurityConfig):
 * requires TenantContext, and a rate-limited request should be rejected
 * before it burns an idempotency claim.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    public static final String REPLAYED_HEADER = "X-Idempotency-Replayed";
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyService idempotencyService;
    private final MeterRegistry meterRegistry;

    public IdempotencyFilter(IdempotencyService idempotencyService, MeterRegistry meterRegistry) {
        this.idempotencyService = idempotencyService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        // No key, or no tenant yet (unauthenticated — authorization will
        // reject downstream): idempotency simply doesn't apply.
        if (key == null || key.isBlank() || TenantContext.getCurrentTenantId() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (key.length() > MAX_KEY_LENGTH) {
            writeJson(response, 400, "{\"status\": 400, \"message\": \"" + IDEMPOTENCY_KEY_HEADER
                + " must be at most " + MAX_KEY_LENGTH + " characters\"}");
            return;
        }

        CachedBodyRequestWrapper cachedRequest = new CachedBodyRequestWrapper(request);
        String requestHash = sha256(request.getMethod() + "|" + request.getRequestURI() + "|"
            + new String(cachedRequest.getBody(), StandardCharsets.UTF_8));

        IdempotencyClaimResult result;
        try {
            idempotencyService.claim(key, requestHash);
            result = new IdempotencyClaimResult.NewClaim();
        } catch (DataIntegrityViolationException duplicate) {
            result = idempotencyService.resolveExisting(key, requestHash);
        }

        switch (result) {
            case IdempotencyClaimResult.Replay replay -> {
                meterRegistry.counter("idempotency.replays.total").increment();
                log.info("Replaying cached response for idempotency key {} (status {})",
                    key, replay.responseStatus());
                response.setStatus(replay.responseStatus());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(REPLAYED_HEADER, "true");
                response.getWriter().write(replay.responseBody() == null ? "" : replay.responseBody());
            }
            case IdempotencyClaimResult.InFlight inFlight -> {
                meterRegistry.counter("idempotency.conflicts.total").increment();
                response.setHeader("Retry-After", "1");
                writeJson(response, 409, "{\"status\": 409, \"message\": \"A request with this "
                    + "idempotency key is already being processed\"}");
            }
            case IdempotencyClaimResult.HashMismatch mismatch -> {
                meterRegistry.counter("idempotency.mismatches.total").increment();
                writeJson(response, 422, "{\"status\": 422, \"message\": \"This idempotency key was "
                    + "already used with a different request body\"}");
            }
            case IdempotencyClaimResult.NewClaim newClaim ->
                processAndCapture(key, cachedRequest, response, filterChain);
        }
    }

    private void processAndCapture(String key,
                                   CachedBodyRequestWrapper request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, captured);
        } catch (Exception ex) {
            // The request never produced a response — drop the claim so the
            // client's retry is not stuck behind a permanent "in flight" row.
            idempotencyService.release(key);
            throw ex;
        }

        int status = captured.getStatus();
        if (isReplayable(status)) {
            idempotencyService.complete(key,
                status, new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8));
        } else {
            idempotencyService.release(key);
        }
        captured.copyBodyToResponse();
    }

    /**
     * 2xx and deterministic 4xx responses replay; 5xx (transient) and
     * auth-state-dependent statuses (401/403/429) must give a retry a real
     * attempt — caching a 403 for 24h would lock a user out long after their
     * role was fixed.
     */
    private boolean isReplayable(int status) {
        if (status >= 500) {
            return false;
        }
        return status != 401 && status != 403 && status != 429;
    }

    /** Only mutating API requests carry idempotency semantics. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean mutating = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        String path = request.getRequestURI();
        return !mutating || !path.startsWith("/api/") || path.startsWith("/api/auth/");
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
