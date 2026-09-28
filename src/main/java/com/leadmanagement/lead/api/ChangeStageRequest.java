package com.leadmanagement.lead.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChangeStageRequest(
    @NotNull UUID stageId
) {}
