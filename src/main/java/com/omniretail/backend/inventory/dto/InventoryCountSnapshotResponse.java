package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Estado FÍSICO de un producto en una ubicación para realizar un conteo. A diferencia de la
 * disponibilidad operacional, incluye mercancía reservada y seriales RESERVED (siguen presentes).
 * {@code serials} agrupa los seriales sin lote; los seriales con lote viajan dentro de cada lote.
 */
public record InventoryCountSnapshotResponse(
        UUID productId,
        String productName,
        String sku,
        UUID branchId,
        String branchName,
        UUID locationId,
        String locationName,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        Tracking tracking,
        List<Lot> lots,
        List<Serial> serials) {

    public record Tracking(boolean lot, boolean expiration, boolean serial) {}

    public record Lot(
            UUID lotId,
            String lotNumber,
            LocalDate expirationDate,
            BigDecimal quantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity,
            List<Serial> serials) {}

    public record Serial(UUID serialId, String serialNumber, InventorySerialStatus status) {}
}
