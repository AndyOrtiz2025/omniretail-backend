package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PurchaseOrderResponse(UUID id, UUID supplierId, UUID branchId, String number, String currency, PurchaseOrderStatus status,
                                    BigDecimal totalAmount, Instant createdAt) {
    public static PurchaseOrderResponse from(PurchaseOrder order) { return new PurchaseOrderResponse(order.getId(),
            order.getSupplierId(), order.getBranchId(), order.getNumber(), order.getCurrency(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt()); }
}
