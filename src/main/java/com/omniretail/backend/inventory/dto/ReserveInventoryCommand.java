package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import java.math.BigDecimal;
import java.util.UUID;

public record ReserveInventoryCommand(
        UUID tenantId,
        UUID branchId,
        UUID productId,
        InventoryReservationSourceType sourceType,
        UUID sourceId,
        UUID sourceLineId,
        UUID orderId,
        UUID orderItemId,
        BigDecimal quantity) {}
