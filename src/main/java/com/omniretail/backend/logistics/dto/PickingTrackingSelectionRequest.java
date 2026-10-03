package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PickingTrackingSelectionRequest(
        @NotNull UUID locationId,
        UUID lotId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal quantity,
        List<@NotBlank @Size(max = 100) String> serialNumbers) {}
