package com.leadmanagement.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant scoping is automatic: @TenantId on IdempotencyRecord makes Hibernate
 * append AND tenant_id = ? to this lookup, so the same idempotency key used
 * by two different tenants resolves to two independent records.
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    void deleteByIdempotencyKey(String idempotencyKey);
}
