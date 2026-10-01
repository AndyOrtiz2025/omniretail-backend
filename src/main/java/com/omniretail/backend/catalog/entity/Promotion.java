package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "promotions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion extends TenantScopedEntity {

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false, length = 200, updatable = false)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20, updatable = false)
    private PromotionDiscountType discountType;

    @NotNull
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discountValue;

    @NotNull
    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", updatable = false)
    private Instant endsAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PromotionStatus status;

    @NotNull
    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public void cancel(UUID actorId, Instant at) {
        if (status == PromotionStatus.cancelled) {
            return;
        }
        status = PromotionStatus.cancelled;
        cancelledByUserId = actorId;
        cancelledAt = at;
    }
}
