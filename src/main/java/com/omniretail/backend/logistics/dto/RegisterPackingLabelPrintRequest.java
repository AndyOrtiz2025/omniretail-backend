package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RegisterPackingLabelPrintRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 128) String operationId,
        @NotBlank @Size(max = 128) String labelGenerationId) {}
