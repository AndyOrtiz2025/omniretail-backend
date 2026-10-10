package com.omniretail.backend.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Conteo trazable; {@code expectedQuantity} protege el snapshot, no representa el resultado contado
 * ni constituye una llave de idempotencia.
 */
public record ReconcileInventoryCountRequest(
        @NotNull UUID branchId,
        @NotNull UUID productId,
        UUID locationId,
        @NotBlank @Size(max = 200) String reason,
        @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal expectedQuantity,
        List<@NotNull @Valid LotCount> lots,
        List<@NotBlank @Size(max = 100) String> expectedSerialNumbers,
        List<@NotBlank @Size(max = 100) String> foundSerialNumbers,
        List<@NotNull @Valid Addition> additions) {

    public record LotCount(
            @NotNull UUID lotId,
            @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal expectedQuantity,
            @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal countedQuantity,
            List<@NotBlank @Size(max = 100) String> expectedSerialNumbers,
            List<@NotBlank @Size(max = 100) String> foundSerialNumbers) {}

    public record Addition(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                    BigDecimal quantity,
            @Size(max = 100) String lotNumber,
            LocalDate expirationDate,
            List<@NotBlank @Size(max = 100) String> serialNumbers) {}
}
