package com.omniretail.backend.pos.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;

@Entity @Table(name = "sale_returns") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SaleReturn extends TenantScopedEntity {
    @Column(name="branch_id", nullable=false) private UUID branchId;
    @Column(name="sale_id", nullable=false) private UUID saleId;
    @Column(name="reason", nullable=false) private String reason;
    @Column(name="refund_amount", nullable=false, precision=12, scale=2) private BigDecimal refundAmount;
    @Column(name="created_by_user_id", nullable=false) private UUID createdByUserId;
}
