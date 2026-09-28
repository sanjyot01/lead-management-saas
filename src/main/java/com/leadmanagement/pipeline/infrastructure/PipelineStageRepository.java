package com.leadmanagement.pipeline.infrastructure;

import com.leadmanagement.pipeline.domain.PipelineStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped automatically via @TenantId on PipelineStage. */
@Repository
public interface PipelineStageRepository extends JpaRepository<PipelineStage, UUID> {

    List<PipelineStage> findByPipelineIdOrderByStageOrder(UUID pipelineId);

    Optional<PipelineStage> findFirstByPipelineIdOrderByStageOrder(UUID pipelineId);
}