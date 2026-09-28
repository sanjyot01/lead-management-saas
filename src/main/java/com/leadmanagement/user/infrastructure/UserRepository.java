package com.leadmanagement.user.infrastructure;

import com.leadmanagement.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entity.
 *
 * Multi-Tenancy:
 * - All queries automatically filtered by tenant_id (Hibernate @TenantId)
 * - findByEmail() only searches within current tenant
 *
 * Example:
 * - Tenant A searches for "bruce@wayne.com" → finds Tenant A's user
 * - Tenant B searches for "bruce@wayne.com" → finds Tenant B's user (different person)
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find user by email within current tenant.
     *
     * Multi-Tenancy: Automatically filtered by tenant_id from TenantContext
     *
     * @param email User's email address
     * @return User if found within current tenant
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if email exists within current tenant.
     *
     * @param email Email to check
     * @return true if email exists in current tenant
     */
    boolean existsByEmail(String email);
}

