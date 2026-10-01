package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "promotion_products")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionProduct extends TenantScopedEntity {

    @NotNull
    @Column(name = "promotion_id", nullable = false, updatable = false)
    private UUID promotionId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;
}
