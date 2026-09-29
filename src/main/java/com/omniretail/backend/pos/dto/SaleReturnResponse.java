package com.omniretail.backend.pos.dto;
import com.omniretail.backend.pos.entity.*; import java.math.BigDecimal; import java.time.Instant; import java.util.*;
public record SaleReturnResponse(UUID id, UUID saleId, UUID branchId, String reason, BigDecimal refundAmount, Instant createdAt, List<Item> lines) {
 public record Item(UUID saleItemId, UUID productId, BigDecimal quantity, BigDecimal refundAmount) {}
 public static SaleReturnResponse from(SaleReturn r,List<SaleReturnItem> items){return new SaleReturnResponse(r.getId(),r.getSaleId(),r.getBranchId(),r.getReason(),r.getRefundAmount(),r.getCreatedAt(),items.stream().map(i->new Item(i.getSaleItemId(),i.getProductId(),i.getQuantity(),i.getRefundAmount())).toList());}
}
