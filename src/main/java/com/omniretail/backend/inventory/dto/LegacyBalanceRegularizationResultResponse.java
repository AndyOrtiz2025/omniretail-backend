package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resultado persistido de una regularizacion. {@code idempotent} es verdadero cuando la respuesta es la
 * ya registrada para la misma clave y la misma solicitud; el resto de campos no cambia entre reintentos.
 *
 * <p>{@code assignmentApplied} indica que esta operacion asigno la ubicacion operativa inicial del producto;
 * {@code previousAssignedLocationId} es la asignacion que tenia antes (null cuando no tenia ninguna).
 * Los resultados persistidos antes de existir estos campos se leen con {@code assignmentApplied = false}.
 */
public record LegacyBalanceRegularizationResultResponse(
        UUID regularizationId,
        boolean idempotent,
        Instant createdAt,
        UUID branchId,
        UUID productId,
        UUID fromLocationId,
        UUID toLocationId,
        BigDecimal movedQuantity,
        BigDecimal movedReservedQuantity,
        BigDecimal destinationQuantityBefore,
        BigDecimal destinationQuantityAfter,
        BigDecimal destinationReservedQuantityAfter,
        int reservationsReassigned,
        int lotBalancesMerged,
        int serialsRelocated,
        UUID movementId,
        boolean assignmentApplied,
        UUID previousAssignedLocationId) {

    public LegacyBalanceRegularizationResultResponse asReplay() {
        return new LegacyBalanceRegularizationResultResponse(
                regularizationId,
                true,
                createdAt,
                branchId,
                productId,
                fromLocationId,
                toLocationId,
                movedQuantity,
                movedReservedQuantity,
                destinationQuantityBefore,
                destinationQuantityAfter,
                destinationReservedQuantityAfter,
                reservationsReassigned,
                lotBalancesMerged,
                serialsRelocated,
                movementId,
                assignmentApplied,
                previousAssignedLocationId);
    }
}
