package com.leadmanagement.tenant.application;

import com.leadmanagement.tenant.domain.Tenant;
import com.leadmanagement.tenant.domain.TenantStatus;
import com.leadmanagement.tenant.infrastructure.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing tenants (organizations).
 *
 * Responsibilities:
 * - Create new tenants
 * - Retrieve tenant information
 * - Activate/suspend tenants
 *
 * Note: Tenant operations are NOT tenant-scoped (no @TenantId filtering)
 * This service can access ALL tenants in the system.
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Create a new tenant.
     *
     * Initial State:
     * - status: TRIAL (14-day trial period)
     * - subscriptionTier: STARTER (can be upgraded)
     * - maxLeads: 1000 (trial limit)
     *
     * @param name Organization name
     * @param slug URL-friendly identifier
     * @return Created tenant
     * @throws IllegalArgumentException if slug already exists
     */
    @Transactional
    public Tenant createTenant(String name, String slug) {
        // Check if slug already exists
        if (tenantRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("Tenant slug already exists: " + slug);
        }

        // Create tenant with trial settings
        Tenant tenant = new Tenant(
            name,
            slug,
            "STARTER",  // Initial subscription tier
            1000        // Trial lead limit
        );

        return tenantRepository.save(tenant);
    }

    /**
     * Find tenant by ID.
     *
     * @param id Tenant UUID
     * @return Tenant if found
     */
    public Optional<Tenant> findById(UUID id) {
        return tenantRepository.findById(id);
    }

    /**
     * Find tenant by slug.
     *
     * @param slug URL-friendly identifier
     * @return Tenant if found
     */
    public Optional<Tenant> findBySlug(String slug) {
        return tenantRepository.findBySlug(slug);
    }

    /**
     * Activate tenant (move from TRIAL to ACTIVE).
     *
     * @param tenantId Tenant UUID
     */
    @Transactional
    public void activateTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        tenant.activate();
        tenantRepository.save(tenant);
    }

    /**
     * Suspend tenant (payment failure or policy violation).
     *
     * @param tenantId Tenant UUID
     */
    @Transactional
    public void suspendTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        tenant.suspend();
        tenantRepository.save(tenant);
    }
}

