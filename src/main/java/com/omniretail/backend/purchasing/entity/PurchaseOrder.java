package com.omniretail.backend.purchasing.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;

@Entity @Table(name = "purchase_orders") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PurchaseOrder extends TenantScopedEntity {
    @NotNull @Column(name = "supplier_id", nullable = false, updatable = false) private UUID supplierId;
    @NotNull @Column(name = "branch_id", nullable = false, updatable = false) private UUID branchId;
    @NotNull @Column(name = "number", nullable = false, updatable = false, length = 50) private String number;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false) private PurchaseOrderStatus status;
    @NotNull @Column(nullable = false, length = 3) private String currency;
    @NotNull @Column(name = "total_amount", nullable = false, precision = 12, scale = 2) private BigDecimal totalAmount;
    @NotNull @Column(name = "created_by_user_id", nullable = false, updatable = false) private UUID createdByUserId;
}
