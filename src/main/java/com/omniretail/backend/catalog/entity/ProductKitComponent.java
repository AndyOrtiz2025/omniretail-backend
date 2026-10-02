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
@Table(name = "product_kit_components")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductKitComponent extends TenantScopedEntity {
    @Column(name = "kit_product_id", nullable = false, updatable = false)
    private UUID kitProductId;
    @Column(name = "component_product_id", nullable = false, updatable = false)
    private UUID componentProductId;
    @Column(name = "quantity_per_kit", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityPerKit;
}
