package com.leadmanagement.pipeline.api;

import com.leadmanagement.pipeline.application.PipelineService;
import com.leadmanagement.pipeline.domain.Pipeline;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Pipeline management endpoints. Tenant scoping comes from the JWT via
 * TenantContext — no tenant parameters anywhere.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /** Lists the tenant's pipelines with their ordered stages. */
    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public List<PipelineResponse> listPipelines() {
        return pipelineService.listPipelines().stream()
            .map(this::toResponse)
            .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public ResponseEntity<PipelineResponse> getPipeline(@PathVariable UUID id) {
        return pipelineService.listPipelines().stream()
            .filter(p -> p.getId().equals(id))
            .findFirst()
            .map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Creates a custom pipeline. Admin-only — pipelines shape the whole team's workflow. */
    @PostMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<PipelineResponse> createPipeline(@Valid @RequestBody CreatePipelineRequest request) {
        Pipeline pipeline = pipelineService.createPipeline(
            request.name(),
            request.stages().stream()
                .map(s -> new PipelineService.NewStage(s.name(), s.probability(), s.terminal()))
                .toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(pipeline));
    }

    private PipelineResponse toResponse(Pipeline pipeline) {
        List<PipelineResponse.StageResponse> stages = pipelineService.listStages(pipeline.getId()).stream()
            .map(s -> new PipelineResponse.StageResponse(
                s.getId(), s.getName(), s.getStageOrder(), s.getProbability(), s.isTerminal()))
            .toList();
        return new PipelineResponse(pipeline.getId(), pipeline.getName(), pipeline.isDefault(), stages);
    }
}
