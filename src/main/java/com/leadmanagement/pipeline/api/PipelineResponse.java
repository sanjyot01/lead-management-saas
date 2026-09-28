package com.leadmanagement.pipeline.api;

import java.util.List;
import java.util.UUID;

public record PipelineResponse(
    UUID id,
    String name,
    boolean isDefault,
    List<StageResponse> stages
) {
    public record StageResponse(
        UUID id,
        String name,
        int stageOrder,
        int probability,
        boolean terminal
    ) {}
}
