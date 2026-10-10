package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consolida el balance heredado sin ubicacion de un producto en su ubicacion operativa asignada.
 *
 * <p>Las cantidades esperadas y {@code snapshotFingerprint} provienen de la vista previa y se revalidan
 * bajo bloqueo: si el estado cambio, la operacion se rechaza (409) sin modificar nada. {@code idempotencyKey}
 * identifica la operacion; repetirla con la misma solicitud devuelve el resultado ya registrado.
 *
 * <p>{@code assignDestination = true} es el modo explicito de asignacion inicial: si el producto no tiene
 * ubicacion asignada en la sucursal, la ubicacion destino se asigna y el saldo se consolida en la misma
 * transaccion. Si ya tiene la ubicacion destino se comporta como el modo normal; si tiene otra, se rechaza.
 * Ausente equivale a {@code false}. Debe coincidir con el modo de la vista previa usada.
 */
public record RegularizeLegacyBalanceRequest(
        @NotNull UUID branchId,
        @NotNull UUID productId,
        @NotNull UUID locationId,
        @NotNull UUID idempotencyKey,
        @NotBlank @Size(max = 200) String reason,
        @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3)
                BigDecimal expectedSourceQuantity,
        @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3)
                BigDecimal expectedSourceReservedQuantity,
        @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3)
                BigDecimal expectedDestinationQuantity,
        @NotBlank @Size(min = 64, max = 64) String snapshotFingerprint,
        Boolean assignDestination) {

    /** Solicitud sin asignacion inicial (comportamiento original). */
    public RegularizeLegacyBalanceRequest(
            UUID branchId,
            UUID productId,
            UUID locationId,
            UUID idempotencyKey,
            String reason,
            BigDecimal expectedSourceQuantity,
            BigDecimal expectedSourceReservedQuantity,
            BigDecimal expectedDestinationQuantity,
            String snapshotFingerprint) {
        this(
                branchId,
                productId,
                locationId,
                idempotencyKey,
                reason,
                expectedSourceQuantity,
                expectedSourceReservedQuantity,
                expectedDestinationQuantity,
                snapshotFingerprint,
                false);
    }

    /** true solo cuando el cuerpo trae {@code assignDestination: true}. */
    public boolean assigning() {
        return Boolean.TRUE.equals(assignDestination);
    }
}
