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
import lombok.Setter;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "promotions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion extends TenantScopedEntity {

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private PromotionDiscountType discountType;

    @NotNull
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @NotNull
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "channels", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> channels = List.of("pos", "ecommerce", "mobileApp");

    @Column(name = "until_stock_ends", nullable = false)
    @Builder.Default
    private Boolean untilStockEnds = false;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "branch_ids", nullable = false, columnDefinition = "uuid[]")
    @Builder.Default
    private List<UUID> branchIds = List.of();

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

    public void end(Instant at) {
        if (status == PromotionStatus.cancelled || status == PromotionStatus.ended) return;
        status = PromotionStatus.ended;
        endsAt = at.isAfter(startsAt) ? at : startsAt.plusMillis(1);
    }
}
