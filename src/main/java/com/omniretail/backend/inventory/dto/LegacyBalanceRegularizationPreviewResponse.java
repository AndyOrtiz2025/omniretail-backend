package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Vista previa READ-ONLY de la regularizacion. {@code eligible} es verdadero solo si no hay bloqueos;
 * {@code snapshotFingerprint} y las cantidades esperadas se reenvian al ejecutar.
 *
 * <p>{@code assignedLocationId} es la ubicacion operativa asignada hoy al producto en la sucursal (null si
 * no tiene). {@code assignmentRequired} indica que el destino aun no esta asignado (asignarlo exige el modo
 * {@code assignDestination}). {@code assignmentAllowed} indica que, con ese modo, la politica y los permisos
 * del usuario permitirian asignarlo; los bloqueos de inventario siguen reportandose en {@code blockers}.
 */
public record LegacyBalanceRegularizationPreviewResponse(
        UUID branchId,
        UUID productId,
        String productName,
        String sku,
        UUID locationId,
        String locationName,
        boolean eligible,
        List<Blocker> blockers,
        BigDecimal sourceQuantity,
        BigDecimal sourceReservedQuantity,
        BigDecimal destinationQuantity,
        BigDecimal destinationReservedQuantity,
        BigDecimal resultingQuantity,
        BigDecimal resultingReservedQuantity,
        int activeReservations,
        int emptyAllocationReservations,
        int lotBalances,
        int serials,
        String snapshotFingerprint,
        UUID assignedLocationId,
        boolean assignmentRequired,
        boolean assignmentAllowed) {

    public record Blocker(String code, String message) {}
}
