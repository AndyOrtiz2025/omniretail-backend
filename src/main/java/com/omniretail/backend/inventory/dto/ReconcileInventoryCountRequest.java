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
 * Conteo físico trazable. El usuario declara lo que ENCONTRÓ; el backend deriva lo faltante.
 *
 * <ul>
 *   <li>Solo lote: {@code lots[].countedQuantity} por cada lote registrado.
 *   <li>Solo serial: {@code expectedSerialNumbers} (composición física del snapshot) y
 *       {@code foundSerialNumbers} (subconjunto encontrado).
 *   <li>Lote + serial: lo mismo por lote en {@code lots[]}; los campos globales van vacíos.
 *   <li>{@code expectedSerialNumbers} protege la identidad: si la composición física actual no es
 *       exactamente la del snapshot, el conteo se rechaza (stale) y nunca se deriva lo faltante de ella.
 *   <li>{@code expectedQuantity} (total y por lote) es la cantidad registrada que vio el usuario en el
 *       snapshot; solo sirve para detectar un snapshot vencido, nunca como total del conteo.
 * </ul>
 */
public record ReconcileInventoryCountRequest(
        @NotNull UUID branchId,
        @NotNull UUID productId,
        UUID locationId,
        @NotBlank @Size(max = 200) String reason,
        @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal expectedQuantity,
        @Valid List<LotCount> lots,
        List<@NotBlank @Size(max = 100) String> expectedSerialNumbers,
        List<@NotBlank @Size(max = 100) String> foundSerialNumbers,
        @Valid List<Addition> additions) {

    public record LotCount(
            @NotNull UUID lotId,
            @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal expectedQuantity,
            @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal countedQuantity,
            List<@NotBlank @Size(max = 100) String> expectedSerialNumbers,
            List<@NotBlank @Size(max = 100) String> foundSerialNumbers) {}

    /** Unidades encontradas que el sistema no tenía; mismas reglas que una entrada trazable. */
    public record Addition(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                    BigDecimal quantity,
            @Size(max = 100) String lotNumber,
            LocalDate expirationDate,
            List<@NotBlank @Size(max = 100) String> serialNumbers) {}
}
