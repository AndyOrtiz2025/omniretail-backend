package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_sales_price_tiers")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSalesPriceTier extends TenantScopedEntity {
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;
    @Column(name = "min_quantity", nullable = false)
    private Integer minQuantity;
    @Column(name = "unit_price", nullable = false, precision = 9, scale = 2)
    private BigDecimal unitPrice;
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
