package com.omniretail.backend.purchasing.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "supplier_products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierProduct extends TenantScopedEntity {

    @NotNull
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "supplier_sku", length = 100)
    private String supplierSku;

    @NotNull
    @Column(name = "purchase_unit_id", nullable = false)
    private UUID purchaseUnitId;

    @NotNull
    @Column(name = "purchase_to_base_factor", nullable = false, precision = 18, scale = 6)
    private BigDecimal purchaseToBaseFactor;

    @NotNull
    @Column(name = "last_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal lastCost;

    @NotNull
    @Column(name = "lead_time_days", nullable = false)
    private Integer leadTimeDays;

    @NotNull
    @Column(name = "minimum_order_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal minimumOrderQuantity;

    @NotNull
    @Builder.Default
    @Column(name = "preferred", nullable = false)
    private Boolean preferred = false;

    @NotNull
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
