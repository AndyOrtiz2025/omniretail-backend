package com.omniretail.backend.pos.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;

@Entity @Table(name = "sale_return_items") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SaleReturnItem extends TenantScopedEntity {
    @Column(name="return_id", nullable=false) private UUID returnId;
    @Column(name="sale_item_id", nullable=false) private UUID saleItemId;
    @Column(name="product_id", nullable=false) private UUID productId;
    @Column(name="quantity", nullable=false, precision=12, scale=3) private BigDecimal quantity;
    @Column(name="refund_amount", nullable=false, precision=12, scale=2) private BigDecimal refundAmount;
}
