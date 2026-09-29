package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record UpdateInventorySettingsRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal minStock,
        @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal reorderPoint,
        UUID defaultLocationId) {}
