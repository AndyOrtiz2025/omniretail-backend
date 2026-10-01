package com.omniretail.backend.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "product_price_history")
@Immutable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "old_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal newPrice;

    @Column(name = "changed_by_user_id", nullable = false, updatable = false)
    private UUID changedByUserId;

    @Column(name = "reason", length = 200, updatable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
