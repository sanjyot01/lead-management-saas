package com.leadmanagement.pipeline.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePipelineRequest(
    @NotBlank @Size(max = 255) String name,
    @NotEmpty @Valid List<StageDefinition> stages
) {
    public record StageDefinition(
        @NotBlank @Size(max = 255) String name,
        @Min(0) @Max(100) int probability,
        boolean terminal
    ) {}
}
