package com.leadmanagement.user.application;

import com.leadmanagement.infrastructure.security.JwtService;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.infrastructure.web.CorrelationIdFilter;
import com.leadmanagement.tenant.application.TenantService;
import com.leadmanagement.tenant.domain.Tenant;
import com.leadmanagement.tenant.domain.events.TenantRegisteredEvent;
import com.leadmanagement.user.api.dto.AuthResponse;
import com.leadmanagement.user.api.dto.LoginRequest;
import com.leadmanagement.user.api.dto.RegisterRequest;
import com.leadmanagement.user.domain.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Authentication Service - Handles registration and login.
 *
 * Responsibilities:
 * - Register new tenants with admin user
 * - Authenticate users and generate JWT tokens
 *
 * Registration Flow:
 * 1. Create tenant organization
 * 2. Create admin user for that tenant
 * 3. Generate JWT token
 * 4. Return token + user info
 *
 * Login Flow:
 * 1. Find user by email (within tenant)
 * 2. Verify password (BCrypt)
 * 3. Generate JWT token
 * 4. Return token + user info
 *
 * Multi-Tenancy:
 * - Registration creates BOTH tenant AND user in single transaction
 * - Login requires tenant context (from slug in request or JWT)
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final TenantService tenantService;
    private final UserService userService;
    private final JwtService jwtService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Counter authRegisterRequests;
    private final Counter authRegisterErrors;
    private final Counter authLoginRequests;
    private final Counter authLoginErrors;
    private final Timer authRegisterDuration;
    private final Timer authLoginDuration;

    public AuthService(TenantService tenantService, UserService userService, JwtService jwtService,
                       ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.authRegisterRequests = meterRegistry.counter("auth.register.requests.total");
        this.authRegisterErrors = meterRegistry.counter("auth.register.errors.total");
        this.authLoginRequests = meterRegistry.counter("auth.login.requests.total");
        this.authLoginErrors = meterRegistry.counter("auth.login.errors.total");
        this.authRegisterDuration = meterRegistry.timer("auth.register.duration");
        this.authLoginDuration = meterRegistry.timer("auth.login.duration");
    }

    /**
     * Register new tenant with admin user.
     *
     * Process:
     * 1. Create tenant organization
     * 2. Create admin user for that tenant
     * 3. Generate JWT token
     * 4. Return authentication response
     *
     * Transaction:
     * - Tenant creation and admin-user creation are TWO SEPARATE, sequential
     *   transactions — NOT one atomic unit. This is a deliberate constraint,
     *   not an oversight: Hibernate resolves the @TenantId session identifier
     *   once, when a session opens, so the admin User insert needs a session
     *   that opens AFTER tenant.getId() is known. But `users.tenant_id` has a
     *   foreign key to `tenants`, so that session also needs the Tenant row
     *   to be durably committed first — a session opened via REQUIRES_NEW
     *   while the Tenant transaction is still in flight cannot see it yet
     *   (Postgres read-committed isolation) and the FK check fails. The only
     *   way to satisfy both constraints is to let tenant creation commit on
     *   its own before starting the user-creation transaction. This method is
     *   therefore intentionally NOT @Transactional — each call below runs in
     *   its own top-level transaction, via TenantService's / UserService's
     *   own @Transactional methods.
     * - Consequence: if admin-user creation fails after the tenant commits,
     *   the tenant is NOT rolled back (an orphaned tenant with no users, slug
     *   effectively unusable until cleaned up). Accepted trade-off — the
     *   alternative (this whole flow being unable to complete at all) is
     *   strictly worse.
     *
     * @param request Registration details
     * @return Authentication response with JWT token
     */
    public AuthResponse register(RegisterRequest request) {
        authRegisterRequests.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            logger.info("Registering new tenant: {}", request.tenantSlug());
            // Caller (AuthController) has already set a bootstrap TenantContext
            // value — required because Tenant creation below still needs to
            // open SOME session, and Hibernate's SessionFactory refuses to
            // open one without a resolvable tenant identifier once
            // hibernate.tenant_identifier_resolver is configured, even though
            // Tenant itself isn't @TenantId-scoped.

            // Step 1: Create tenant (own transaction — commits before Step 2)
            Tenant tenant = tenantService.createTenant(
                request.tenantName(),
                request.tenantSlug()
            );

            logger.info("Tenant created: {} (ID: {})", tenant.getName(), tenant.getId());

            // Step 2: Create admin user — narrow context to the real tenant
            TenantContext.setCurrentTenantId(tenant.getId());
            User adminUser = userService.createAdminUser(
                tenant.getId(),
                request.adminEmail(),
                request.adminPassword(),
                request.adminFirstName(),
                request.adminLastName()
            );

            logger.info("Admin user created: {} (ID: {})", adminUser.getEmail(), adminUser.getId());

            // Step 2.5: Tenant + admin user are both durably committed —
            // announce the registration. The pipeline module listens and
            // creates the tenant's default pipeline (synchronously: if that
            // fails, registration fails loudly rather than minting a tenant
            // whose lead creation is broken). The event carries the tenantId
            // explicitly; listeners manage their own TenantContext.
            eventPublisher.publishEvent(new TenantRegisteredEvent(
                tenant.getId(),
                tenant.getSlug(),
                CorrelationIdFilter.current()
            ));

            // Step 3: Generate JWT token
            String token = jwtService.generateToken(adminUser);

            // Step 4: Calculate token expiration
            Instant expiresAt = Instant.now().plusMillis(86400000); // 24 hours

            logger.info("Registration successful for tenant: {}", tenant.getSlug());

            return new AuthResponse(
                token,
                adminUser.getId(),
                tenant.getId(),
                adminUser.getEmail(),
                adminUser.getRole().name(),
                expiresAt
            );
        } catch (RuntimeException ex) {
            authRegisterErrors.increment();
            throw ex;
        } finally {
            TenantContext.clear();
            sample.stop(authRegisterDuration);
        }
    }

    /**
     * Authenticate user and generate JWT token.
     *
     * Process:
     * 1. Set tenant context (from tenant slug)
     * 2. Find user by email
     * 3. Verify password
     * 4. Generate JWT token
     * 5. Return authentication response
     *
     * Multi-Tenancy:
     * - Tenant slug is provided in request body or header
     * - Sets TenantContext before authentication
     * - User lookup is automatically filtered by tenant_id
     *
     * @param request Login credentials
     * @param tenantSlug Tenant identifier
     * @return Authentication response with JWT token
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String tenantSlug) {
        authLoginRequests.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        logger.info("Login attempt for email: {} (tenant: {})", request.email(), tenantSlug);
        // Caller (AuthController) has already set a bootstrap TenantContext
        // value — see the comment in register() for why.

        try {
            // Step 1: Find tenant by slug
            Tenant tenant = tenantService.findBySlug(tenantSlug)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantSlug));

            // Step 2: Narrow tenant context to the real tenant for user lookup
            TenantContext.setCurrentTenantId(tenant.getId());

            // Step 3: Authenticate user (verify password)
            User user = userService.authenticate(request.email(), request.password());

            logger.info("Authentication successful for user: {} (tenant: {})", user.getEmail(), tenant.getSlug());

            // Step 4: Generate JWT token
            String token = jwtService.generateToken(user);

            // Step 5: Calculate token expiration
            Instant expiresAt = Instant.now().plusMillis(86400000); // 24 hours

            return new AuthResponse(
                token,
                user.getId(),
                tenant.getId(),
                user.getEmail(),
                user.getRole().name(),
                expiresAt
            );

        } catch (RuntimeException ex) {
            authLoginErrors.increment();
            throw ex;
        } finally {
            // Always clear tenant context
            TenantContext.clear();
            sample.stop(authLoginDuration);
        }
    }
}

