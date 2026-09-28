package com.leadmanagement.user.application;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.user.domain.User;
import com.leadmanagement.user.domain.UserRole;
import com.leadmanagement.user.infrastructure.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing users.
 *
 * Responsibilities:
 * - Create users (with password hashing)
 * - Authenticate users (verify password)
 * - Retrieve user information
 *
 * Multi-Tenancy:
 * - All operations automatically filtered by tenant_id (Hibernate @TenantId)
 * - findByEmail() only searches within current tenant
 *
 * Security:
 * - Passwords are ALWAYS hashed with BCrypt (never stored plaintext)
 * - Password verification uses BCrypt comparison (timing-safe)
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create a new user within current tenant.
     *
     * Security:
     * - Password is hashed with BCrypt before storage
     *
     * @param email User's email
     * @param password Plaintext password (will be hashed)
     * @param firstName First name
     * @param lastName Last name
     * @param role User role
     * @return Created user
     * @throws IllegalArgumentException if email already exists in tenant
     */
    @Transactional
    public User createUser(String email, String password, String firstName, String lastName, UserRole role) {
        // Check if email already exists in current tenant
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("User with email already exists: " + email);
        }

        // Hash password with BCrypt (strength: 10 rounds)
        String passwordHash = passwordEncoder.encode(password);

        // Create user
        User user = new User(email, passwordHash, firstName, lastName, role);

        // flush (not just save) — @TenantId is an on-execution generator; its
        // value only lands on the in-memory entity once the INSERT actually
        // runs. Callers (e.g. JwtService.generateToken) read user.getTenantId()
        // immediately after this returns, so it must already be populated.
        return userRepository.saveAndFlush(user);
    }

    /**
     * Create admin user (first user for a tenant).
     *
     * This method is called during tenant registration.
     * It temporarily sets TenantContext to allow user creation.
     *
     * @param tenantId Tenant UUID
     * @param email Admin email
     * @param password Admin password
     * @param firstName First name
     * @param lastName Last name
     * @return Created admin user
     *
     * Runs as its own top-level transaction (see AuthService.register()'s
     * javadoc for why this can't be nested inside the Tenant-creation
     * transaction). Caller must set TenantContext to the real tenantId BEFORE
     * calling this method — the proxy opens the session at the call boundary,
     * before this method's own body (including the line below) ever runs, and
     * Hibernate resolves the @TenantId session identifier once, at that open.
     */
    @Transactional
    public User createAdminUser(UUID tenantId, String email, String password, String firstName, String lastName) {
        try {
            // Belt-and-suspenders for any caller that didn't already set it.
            TenantContext.setCurrentTenantId(tenantId);

            return createUser(email, password, firstName, lastName, UserRole.ADMIN);

        } finally {
            // Clear context (important for thread safety)
            TenantContext.clear();
        }
    }

    /**
     * Authenticate user by email and password.
     *
     * Process:
     * 1. Find user by email (within current tenant)
     * 2. Verify password (BCrypt comparison)
     * 3. Check if user is active
     * 4. Return user if authenticated
     *
     * @param email User's email
     * @param password Plaintext password
     * @return User if authentication successful
     * @throws IllegalArgumentException if authentication fails
     *
     * REQUIRES_NEW for the same reason as createAdminUser(): login() already
     * has a transaction open (from the tenant-by-slug lookup, which predates
     * knowing the real tenant), so this needs a fresh session to pick up the
     * tenant ID login() sets right before calling this method.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public User authenticate(String email, String password) {
        // Find user by email (automatically filtered by tenant_id)
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        // Verify password (timing-safe BCrypt comparison)
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        // Check if user is active
        if (!user.isActive()) {
            throw new IllegalArgumentException("User account is disabled");
        }

        return user;
    }

    /**
     * Find user by ID within current tenant.
     *
     * @param id User UUID
     * @return User if found
     */
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    /**
     * Find user by email within current tenant.
     *
     * @param email User's email
     * @return User if found
     */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Disable user account (soft delete).
     *
     * @param userId User UUID
     */
    @Transactional
    public void disableUser(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.setActive(false);
        userRepository.save(user);
    }
}

