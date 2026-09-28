package com.leadmanagement.infrastructure.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT Authentication Filter - Extracts and validates JWT tokens.
 *
 * Request Flow:
 * 1. Client sends: Authorization: Bearer {JWT-token}
 * 2. Filter extracts token from header
 * 3. Validates token signature and expiration
 * 4. Extracts claims: userId, tenantId, role
 * 5. Sets Spring Security context (for authorization)
 * 6. Sets TenantContext (for Hibernate @TenantId filtering)
 * 7. Proceeds to controller
 * 8. Clears contexts after request (prevents thread leaks)
 *
 * Security Notes:
 * - Runs on EVERY request to protected endpoints
 * - Skips /api/auth/** (login, register don't need tokens)
 * - Thread-safe: Uses ThreadLocal for contexts
 * - Always clears contexts in finally block
 *
 * Example:
 * Request Header: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
 * → Extracts: userId=123, tenantId=456, role=ADMIN
 * → Sets contexts
 * → Controller can access authenticated user
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String TENANT_MDC_KEY = "tenantId";
    private static final String USER_MDC_KEY = "userId";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            // Extract JWT token from Authorization header
            String token = extractTokenFromRequest(request);

            if (token != null) {
                // Validate token and extract claims
                Claims claims = jwtService.validateToken(token);

                // Extract user information from token
                String userId = claims.get("userId", String.class);
                UUID tenantId = UUID.fromString(claims.get("tenantId", String.class));
                String email = claims.get("email", String.class);
                String role = mapAuthorityRole(claims.get("role", String.class));

                logger.debug("JWT validated for user: {} (tenant: {}, role: {})", email, tenantId, role);

                // Set Spring Security context (for authorization)
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        userId,  // Principal (user ID)
                        null,    // Credentials (not needed, already authenticated)
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))  // Authorities
                    );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Set Tenant Context (for Hibernate @TenantId filtering)
                TenantContext.setCurrentTenantId(tenantId);
                MDC.put(TENANT_MDC_KEY, tenantId.toString());
                MDC.put(USER_MDC_KEY, userId);

                logger.debug("Security context and tenant context set for tenant: {}", tenantId);

            } else {
                logger.debug("No JWT token found in request to: {}", request.getRequestURI());
            }

            // Continue to next filter/controller
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            logger.error("JWT authentication failed: {}", e.getMessage());
            // Clear contexts on error
            SecurityContextHolder.clearContext();
            TenantContext.clear();

            // Return 401 Unauthorized
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\": \"Invalid or expired token\"}");
            response.setContentType("application/json");

        } finally {
            // CRITICAL: Always clear contexts after request (prevent thread leaks)
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            MDC.remove(TENANT_MDC_KEY);
            MDC.remove(USER_MDC_KEY);
        }
    }

    private String mapAuthorityRole(String role) {
        if ("ADMIN".equals(role) || "TENANT_ADMIN".equals(role)) {
            return "TENANT_ADMIN";
        }
        if ("MEMBER".equals(role) || "REP".equals(role)) {
            return "REP";
        }
        return role;
    }

    /**
     * Extract JWT token from Authorization header.
     *
     * Expected format: Authorization: Bearer {token}
     *
     * @param request HTTP request
     * @return JWT token string or null if not found
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7); // Remove "Bearer " prefix
        }

        return null;
    }

    /**
     * Skip filter for public endpoints (login, register).
     *
     * @param request HTTP request
     * @return true if filter should be skipped
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip authentication for public endpoints
        return path.startsWith("/api/auth/") ||
               path.startsWith("/actuator/health");
    }
}

