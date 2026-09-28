package com.leadmanagement.pipeline.infrastructure;

import com.leadmanagement.pipeline.domain.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * All lookups are automatically tenant-scoped via @TenantId on BaseEntity
 * and exclude soft-deleted rows via @SQLRestriction.
 */
@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {

    Optional<Pipeline> findByIsDefaultTrue();

    boolean existsByIsDefaultTrue();
}