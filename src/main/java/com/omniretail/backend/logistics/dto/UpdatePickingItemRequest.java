package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record UpdatePickingItemRequest(
        @NotNull @DecimalMin("0.000") BigDecimal pickedQuantity,
        UUID locationId,
        @NotBlank @Size(max = 128) String operationId) {}
