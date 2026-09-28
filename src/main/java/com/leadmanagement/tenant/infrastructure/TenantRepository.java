package com.leadmanagement.tenant.infrastructure;

import com.leadmanagement.tenant.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Tenant entity.
 *
 * Note: Tenant does NOT have @TenantId (tenants don't belong to a tenant)
 * All methods query across ALL tenants (no automatic filtering)
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /**
     * Find tenant by slug (e.g., "wayne-enterprises")
     *
     * @param slug URL-friendly identifier
     * @return Tenant if found
     */
    Optional<Tenant> findBySlug(String slug);

    /**
     * Check if slug already exists
     *
     * @param slug Slug to check
     * @return true if slug exists
     */
    boolean existsBySlug(String slug);
}

