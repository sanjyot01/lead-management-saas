package com.leadmanagement.lead.infrastructure;

import com.leadmanagement.lead.domain.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Lead entity.
 *
 * Hibernate automatically filters all queries by tenant_id thanks to @TenantId annotation.
 * No need to manually add tenant_id to WHERE clauses.
 */
@Repository
public interface LeadRepository extends JpaRepository<Lead, UUID> {

    /**
     * Find a lead by email.
     * Automatically scoped to current tenant via @TenantId.
     *
     * @param email the lead's email address
     * @return Optional containing the lead if found, empty otherwise
     */
    Optional<Lead> findByEmail(String email);
}

