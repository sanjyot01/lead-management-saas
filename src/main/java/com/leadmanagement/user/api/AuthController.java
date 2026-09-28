package com.leadmanagement.user.api;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.user.api.dto.AuthResponse;
import com.leadmanagement.user.api.dto.LoginRequest;
import com.leadmanagement.user.api.dto.RegisterRequest;
import com.leadmanagement.user.application.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Authentication Controller - Public endpoints for registration and login.
 *
 * Endpoints:
 * - POST /api/auth/register - Register new tenant + admin user
 * - POST /api/auth/login - Login and get JWT token
 *
 * Security:
 * - These endpoints are PUBLIC (no JWT required)
 * - Configured in SecurityConfig to bypass authentication
 *
 * Usage Examples:
 *
 * Register:
 * POST /api/auth/register
 * {
 *   "tenantName": "Wayne Enterprises",
 *   "tenantSlug": "wayne-enterprises",
 *   "adminEmail": "bruce@wayne.com",
 *   "adminPassword": "BatmanRocks123!",
 *   "adminFirstName": "Bruce",
 *   "adminLastName": "Wayne"
 * }
 * Response: { "token": "...", "userId": "...", "tenantId": "..." }
 *
 * Login:
 * POST /api/auth/login
 * {
 *   "email": "bruce@wayne.com",
 *   "password": "BatmanRocks123!",
 *   "tenantSlug": "wayne-enterprises"
 * }
 * Response: { "token": "...", "userId": "...", "tenantId": "..." }
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Register new tenant with admin user.
     *
     * Creates:
     * 1. Tenant organization
     * 2. Admin user for that tenant
     * 3. JWT token for immediate login
     *
     * @param request Registration details
     * @return JWT token + user info (201 Created)
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        logger.info("Registration request for tenant: {}", request.tenantSlug());

        // Bootstrap tenant context here, before entering the @Transactional
        // service method. Spring's transactional proxy opens the JPA session
        // when it intercepts the call — before any statement in the service
        // method body runs — so setting TenantContext inside authService's
        // own method is too late to satisfy Hibernate's
        // hibernate.tenant_identifier_resolver gate. Tenant itself isn't
        // @TenantId-scoped, so this throwaway value never reaches a column;
        // it only unblocks session creation.
        TenantContext.setCurrentTenantId(UUID.randomUUID());
        try {
            AuthResponse response = authService.register(request);

            logger.info("Registration successful for tenant: {}", request.tenantSlug());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            logger.error("Registration failed: {}", e.getMessage());
            throw e;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Login user and generate JWT token.
     *
     * Request Body Extension (includes tenantSlug):
     * We need to know which tenant the user belongs to.
     * Client should send tenantSlug in request body.
     *
     * @param request Login credentials + tenant slug
     * @return JWT token + user info (200 OK)
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequestWithTenant request) {
        logger.info("Login attempt for email: {} (tenant: {})", request.email(), request.tenantSlug());

        // Same bootstrap requirement as register() above — must happen before
        // the @Transactional service call, not inside it.
        TenantContext.setCurrentTenantId(UUID.randomUUID());
        try {
            // Create LoginRequest from extended request
            LoginRequest loginRequest = new LoginRequest(request.email(), request.password());

            AuthResponse response = authService.login(loginRequest, request.tenantSlug());

            logger.info("Login successful for email: {} (tenant: {})", request.email(), request.tenantSlug());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.error("Login failed: {}", e.getMessage());
            // Don't leak information about whether email exists
            throw new IllegalArgumentException("Invalid credentials");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Extended login request with tenant slug.
     *
     * Why needed:
     * - User's email may exist in multiple tenants
     * - We need to know which tenant to authenticate against
     *
     * Example:
     * {
     *   "email": "admin@example.com",
     *   "password": "Password123!",
     *   "tenantSlug": "wayne-enterprises"
     * }
     */
    public record LoginRequestWithTenant(
        String email,
        String password,
        String tenantSlug
    ) {
    }
}

