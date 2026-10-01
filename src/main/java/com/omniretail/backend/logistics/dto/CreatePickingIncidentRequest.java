package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.logistics.entity.PickingIncidentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreatePickingIncidentRequest(
        UUID pickingLineId,
        @NotNull PickingIncidentType type,
        @DecimalMin(value = "0.000", inclusive = false) BigDecimal quantityAffected,
        @NotBlank @Size(max = 500) String comment) {}
