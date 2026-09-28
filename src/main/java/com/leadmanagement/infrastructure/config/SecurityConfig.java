package com.leadmanagement.infrastructure.config;

import com.leadmanagement.infrastructure.idempotency.IdempotencyFilter;
import com.leadmanagement.infrastructure.security.JwtAuthenticationFilter;
import com.leadmanagement.infrastructure.web.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration with JWT authentication.
 *
 * Security Architecture:
 * - Stateless sessions (no server-side session storage)
 * - JWT-based authentication (Bearer token in Authorization header)
 * - Public endpoints: /api/auth/** (register, login)
 * - Protected endpoints: /api/** (require valid JWT token)
 *
 * Request Flow:
 * 1. Client sends Authorization: Bearer {token}
 * 2. JwtAuthenticationFilter validates token
 * 3. Sets Spring Security context + TenantContext
 * 4. Controller processes request
 * 5. Filter clears contexts (prevents thread leaks)
 *
 * Password Security:
 * - BCrypt with 10 rounds (strength parameter)
 * - Timing-safe password comparison
 * - Passwords never stored in plaintext
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final IdempotencyFilter idempotencyFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          IdempotencyFilter idempotencyFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.idempotencyFilter = idempotencyFilter;
    }

    /**
     * Configure HTTP security with JWT authentication.
     *
     * Security Rules:
     * - /api/auth/** - Public (register, login)
     * - /actuator/health - Public (health checks)
     * - /api/** - Protected (requires JWT token)
     *
     * Session Management:
     * - STATELESS (no server-side sessions)
     * - All state in JWT token
     *
     * Filters:
     * - JwtAuthenticationFilter runs BEFORE standard authentication
     * - Extracts and validates JWT token
     * - Sets security context
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF (not needed for stateless JWT API)
                .csrf(AbstractHttpConfigurer::disable)

                // Stateless session management (no server-side sessions)
                .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                    // Public endpoints (no authentication required)
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()

                    // All other /api/** endpoints require authentication
                    .requestMatchers("/api/**").authenticated()

                    // Deny everything else
                    .anyRequest().denyAll()
                )

                // Add JWT filter BEFORE standard authentication
                .addFilterBefore(
                    jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class
                )

                // Phase 5 filters run INSIDE JwtAuthenticationFilter's scope —
                // both need TenantContext, which the JWT filter sets before the
                // downstream chain and clears in its finally block.
                // Rate limit BEFORE idempotency: a throttled request should be
                // rejected before it burns an idempotency claim.
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(idempotencyFilter, RateLimitFilter.class);

        return http.build();
    }

    /**
     * Password encoder for hashing passwords.
     *
     * BCrypt Details:
     * - Adaptive hashing (automatically salted)
     * - Strength: 10 rounds (2^10 iterations)
     * - Timing-safe comparison (prevents timing attacks)
     *
     * Hash format: $2a$10$... (60 characters)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

