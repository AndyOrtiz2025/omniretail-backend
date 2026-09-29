package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.Sale;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SaleResponse(UUID id, String number, UUID branchId, UUID cashShiftId, BigDecimal subtotal,
                           BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total, Instant createdAt) {
    public static SaleResponse from(Sale sale) { return new SaleResponse(sale.getId(), sale.getNumber(), sale.getBranchId(),
            sale.getCashShiftId(), sale.getSubtotal(), sale.getDiscountTotal(), sale.getTaxTotal(), sale.getTotal(), sale.getCreatedAt()); }
}
