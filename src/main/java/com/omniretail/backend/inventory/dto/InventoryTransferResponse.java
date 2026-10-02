package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryTransferResponse(
        UUID id,
        String number,
        UUID sourceBranchId,
        UUID destinationBranchId,
        InventoryTransferStatus status,
        InventoryTransferReason reason,
        String notes,
        List<InventoryTransferItemResponse> items,
        UUID preparedByUserId,
        Instant preparedAt,
        UUID dispatchedByUserId,
        Instant dispatchedAt,
        UUID receivedByUserId,
        Instant receivedAt,
        UUID cancelledByUserId,
        Instant cancelledAt,
        String cancelReason,
        Long version,
        Instant createdAt,
        Instant updatedAt) {}
