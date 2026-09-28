package com.leadmanagement.pipeline.application;

import com.leadmanagement.pipeline.domain.Pipeline;
import com.leadmanagement.pipeline.domain.PipelineStage;
import com.leadmanagement.pipeline.infrastructure.PipelineRepository;
import com.leadmanagement.pipeline.infrastructure.PipelineStageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pipeline management for the current tenant (all queries auto-scoped via
 * @TenantId — callers never pass a tenant ID).
 *
 * Also the pipeline module's API for the lead module (an allowed module
 * dependency): defaultEntryPoint() replaces the placeholder UUIDs LeadService
 * carried since Phase 1, and findStage() backs stage-transition validation.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    /**
     * The classic B2B funnel, created for every tenant at registration.
     * name / probability / terminal — order is the array position.
     */
    private static final StageSpec[] DEFAULT_STAGES = {
        new StageSpec("New", 10, false),
        new StageSpec("Contacted", 25, false),
        new StageSpec("Qualified", 50, false),
        new StageSpec("Proposal Sent", 75, false),
        new StageSpec("Negotiation", 90, false),
        new StageSpec("Won", 100, true),
        new StageSpec("Lost", 0, true),
    };

    private record StageSpec(String name, int probability, boolean terminal) {}

    private final PipelineRepository pipelineRepository;
    private final PipelineStageRepository stageRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                           PipelineStageRepository stageRepository) {
        this.pipelineRepository = pipelineRepository;
        this.stageRepository = stageRepository;
    }

    /**
     * Creates the tenant's default pipeline with the standard stage funnel.
     * Idempotent: a tenant that already has a default keeps it untouched
     * (matters if a registration retry ever replays the event).
     */
    @Transactional
    public Pipeline createDefaultPipeline() {
        Optional<Pipeline> existing = pipelineRepository.findByIsDefaultTrue();
        if (existing.isPresent()) {
            log.info("Default pipeline already exists ({}), skipping creation", existing.get().getId());
            return existing.get();
        }

        Pipeline pipeline = pipelineRepository.save(new Pipeline("Default Pipeline", true));
        for (int i = 0; i < DEFAULT_STAGES.length; i++) {
            StageSpec spec = DEFAULT_STAGES[i];
            stageRepository.save(new PipelineStage(
                pipeline.getId(), spec.name(), i + 1, spec.probability(), spec.terminal()));
        }
        log.info("Created default pipeline {} with {} stages", pipeline.getId(), DEFAULT_STAGES.length);
        return pipeline;
    }

    /**
     * Where a brand-new lead enters: the default pipeline and its first stage.
     *
     * @throws IllegalStateException if the tenant has no default pipeline —
     *         a registration-time invariant was broken; failing loudly beats
     *         inventing placeholder UUIDs (which is exactly what Phase 1's
     *         scaffold did and Phase 6 removed).
     */
    @Transactional(readOnly = true)
    public PipelineEntryPoint defaultEntryPoint() {
        Pipeline pipeline = pipelineRepository.findByIsDefaultTrue()
            .orElseThrow(() -> new IllegalStateException(
                "Tenant has no default pipeline — it should have been created at registration"));
        PipelineStage firstStage = stageRepository
            .findFirstByPipelineIdOrderByStageOrder(pipeline.getId())
            .orElseThrow(() -> new IllegalStateException(
                "Default pipeline " + pipeline.getId() + " has no stages"));
        return new PipelineEntryPoint(pipeline.getId(), firstStage.getId());
    }

    /** Stage lookup for transition validation (tenant-scoped automatically). */
    @Transactional(readOnly = true)
    public Optional<PipelineStage> findStage(UUID stageId) {
        return stageRepository.findById(stageId);
    }

    @Transactional(readOnly = true)
    public List<Pipeline> listPipelines() {
        return pipelineRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<PipelineStage> listStages(UUID pipelineId) {
        return stageRepository.findByPipelineIdOrderByStageOrder(pipelineId);
    }

    /** Creates a custom (non-default) pipeline with caller-defined stages. */
    @Transactional
    public Pipeline createPipeline(String name, List<NewStage> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("A pipeline needs at least one stage");
        }
        Pipeline pipeline = pipelineRepository.save(new Pipeline(name, false));
        for (int i = 0; i < stages.size(); i++) {
            NewStage s = stages.get(i);
            stageRepository.save(new PipelineStage(
                pipeline.getId(), s.name(), i + 1, s.probability(), s.terminal()));
        }
        return pipeline;
    }

    /** Stage definition supplied when creating a custom pipeline. */
    public record NewStage(String name, int probability, boolean terminal) {}

    /** The (pipeline, first stage) pair a new lead starts in. */
    public record PipelineEntryPoint(UUID pipelineId, UUID firstStageId) {}
}
