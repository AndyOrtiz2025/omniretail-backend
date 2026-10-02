package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.logistics.entity.PickingItemStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PickingLineResponse(
        UUID pickingLineId,
        UUID orderItemId,
        UUID productId,
        String sku,
        String name,
        BigDecimal requiredQuantity,
        BigDecimal pickedQuantity,
        BigDecimal remainingQuantity,
        PickingItemStatus status,
        Location location,
        Lot lot,
        List<String> serialNumbers,
        List<AvailableLocation> availableLocations,
        List<AvailableLot> availableLots,
        List<String> availableSerialNumbers,
        Tracking tracking,
        InventoryAvailability inventory,
        UUID sourceLineId) {

    public PickingLineResponse(
            UUID pickingLineId,
            UUID orderItemId,
            UUID productId,
            String sku,
            String name,
            BigDecimal requiredQuantity,
            BigDecimal pickedQuantity,
            BigDecimal remainingQuantity,
            PickingItemStatus status,
            Location location,
            Lot lot,
            List<String> serialNumbers,
            List<AvailableLocation> availableLocations,
            List<AvailableLot> availableLots,
            List<String> availableSerialNumbers,
            Tracking tracking,
            InventoryAvailability inventory) {
        this(
                pickingLineId,
                orderItemId,
                productId,
                sku,
                name,
                requiredQuantity,
                pickedQuantity,
                remainingQuantity,
                status,
                location,
                lot,
                serialNumbers,
                availableLocations,
                availableLots,
                availableSerialNumbers,
                tracking,
                inventory,
                orderItemId);
    }

    public record Location(UUID id, String code, String name) {}

    public record Lot(UUID id, String number) {}

    public record Tracking(boolean stock, boolean lot, boolean expiration, boolean serial) {}

    public record AvailableLocation(
            UUID id,
            String code,
            String name,
            BigDecimal ownReservedQuantity,
            BigDecimal usableQuantity) {}

    public record AvailableLot(
            UUID id,
            String number,
            String expirationDate,
            BigDecimal physicalQuantity) {}

    public record InventoryAvailability(
            UUID tenantId,
            UUID branchId,
            UUID pickingOrderId,
            UUID orderId,
            UUID productId,
            BigDecimal physicalQuantity,
            BigDecimal ownReservedQuantity,
            BigDecimal otherReservedQuantity,
            BigDecimal freeQuantity,
            BigDecimal usableQuantity,
            List<InventoryLocation> locations) {}

    public record InventoryLocation(
            UUID balanceId,
            UUID locationId,
            String locationCode,
            String locationName,
            BigDecimal physicalQuantity,
            BigDecimal ownReservedQuantity,
            BigDecimal otherReservedQuantity,
            BigDecimal freeQuantity,
            BigDecimal usableQuantity,
            List<InventoryLot> lots,
            List<InventorySerial> serialNumbers) {}

    public record InventoryLot(
            UUID lotId,
            String lotNumber,
            String expirationDate,
            BigDecimal physicalQuantity,
            List<InventoryLotSerial> serialNumbers) {}

    public record InventoryLotSerial(UUID id, String serialNumber) {}

    public record InventorySerial(UUID id, String serialNumber, UUID lotId) {}
}
