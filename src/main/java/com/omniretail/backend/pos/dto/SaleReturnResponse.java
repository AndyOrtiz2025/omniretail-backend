package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.SaleReturn;
import com.omniretail.backend.pos.entity.SaleReturnItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleReturnResponse(
        UUID id,
        UUID saleId,
        UUID branchId,
        String reason,
        BigDecimal refundAmount,
        Instant createdAt,
        List<Item> lines) {

    public record Item(UUID saleItemId, UUID productId, BigDecimal quantity, BigDecimal refundAmount) {}

    public static SaleReturnResponse from(SaleReturn saleReturn, List<SaleReturnItem> items) {
        return new SaleReturnResponse(
                saleReturn.getId(),
                saleReturn.getSaleId(),
                saleReturn.getBranchId(),
                saleReturn.getReason(),
                saleReturn.getRefundAmount(),
                saleReturn.getCreatedAt(),
                items.stream()
                        .map(item -> new Item(item.getSaleItemId(), item.getProductId(), item.getQuantity(), item.getRefundAmount()))
                        .toList());
    }
}
