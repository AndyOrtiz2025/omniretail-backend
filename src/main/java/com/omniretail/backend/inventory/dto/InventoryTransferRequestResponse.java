package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryTransferRequestResponse(
        UUID id,
        UUID requestingBranchId,
        UUID sourceBranchId,
        UUID productId,
        BigDecimal requestedQuantity,
        InventoryTransferReason reason,
        String notes,
        InventoryTransferRequestStatus persistedStatus,
        InventoryTransferRequestEffectiveStatus effectiveStatus,
        UUID requestedByUserId,
        Instant requestedAt,
        UUID reviewedByUserId,
        Instant reviewedAt,
        String reviewNotes,
        UUID transferId,
        Long version,
        Instant createdAt,
        Instant updatedAt) {}
