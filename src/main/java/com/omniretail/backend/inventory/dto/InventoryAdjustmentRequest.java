package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ajuste manual de inventario. {@code expectedQuantity} es una precondicion optimista sobre el
 * balance fisico bloqueado; no es una llave de idempotencia y no evita por si sola duplicados de
 * reintentos que vuelvan a coincidir con la misma cantidad.
 */
public record InventoryAdjustmentRequest(
        @NotNull UUID branchId,
        @NotNull UUID productId,
        @NotNull InventoryAdjustmentType type,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal quantity,
        @NotBlank @Size(max = 200) String reason,
        @Size(max = 50) String referenceType,
        UUID referenceId,
        UUID locationId,
        UUID lotId,
        @Size(max = 100) String lotNumber,
        LocalDate expirationDate,
        List<@NotBlank @Size(max = 100) String> serialNumbers,
        @DecimalMin(value = "0", inclusive = true) @Digits(integer = 9, fraction = 3)
                BigDecimal expectedQuantity) {

    public InventoryAdjustmentRequest(
            UUID branchId,
            UUID productId,
            InventoryAdjustmentType type,
            BigDecimal quantity,
            String reason,
            String referenceType,
            UUID referenceId,
            UUID locationId,
            UUID lotId,
            String lotNumber,
            LocalDate expirationDate,
            List<String> serialNumbers) {
        this(
                branchId,
                productId,
                type,
                quantity,
                reason,
                referenceType,
                referenceId,
                locationId,
                lotId,
                lotNumber,
                expirationDate,
                serialNumbers,
                null);
    }
}
