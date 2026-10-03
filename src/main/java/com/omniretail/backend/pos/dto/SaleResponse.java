package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SaleResponse(
        UUID id,
        String number,
        UUID branchId,
        UUID cashShiftId,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        Instant createdAt,
        SaleStatus status,
        UUID customerId,
        UUID sourceOrderId,
        SaleDocumentResponse document) {

    public static SaleResponse from(Sale sale) {
        return new SaleResponse(
                sale.getId(),
                sale.getNumber(),
                sale.getBranchId(),
                sale.getCashShiftId(),
                sale.getSubtotal(),
                sale.getDiscountTotal(),
                sale.getTaxTotal(),
                sale.getTotal(),
                sale.getCreatedAt(),
                sale.getStatus(),
                sale.getCustomerId(),
                sale.getSourceOrderId(),
                SaleDocumentResponse.from(sale));
    }
}
