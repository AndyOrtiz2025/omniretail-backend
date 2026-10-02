package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryTransferReceiptResponse(
        UUID id,
        UUID transferId,
        String confirmationId,
        UUID destinationBranchId,
        UUID destinationLocationId,
        UUID receivedByUserId,
        Instant receivedAt,
        List<InventoryTransferReceiptItemResponse> items,
        InventoryTransferStatus transferStatus,
        boolean idempotent) {}
