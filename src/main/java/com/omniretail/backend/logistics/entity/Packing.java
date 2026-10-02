package com.omniretail.backend.logistics.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "packings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Packing extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private PackingSourceType sourceType;

    @NotNull
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @NotNull
    @Column(name = "picking_order_id", nullable = false, updatable = false)
    private UUID pickingOrderId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PackingStatus status = PackingStatus.in_progress;

    @Builder.Default
    @Column(name = "package_protection_checked", nullable = false)
    private boolean packageProtectionChecked = false;

    @Builder.Default
    @Column(name = "document_included_checked", nullable = false)
    private boolean documentIncludedChecked = false;

    @Builder.Default
    @Column(name = "recipient_verified_checked", nullable = false)
    private boolean recipientVerifiedChecked = false;

    @DecimalMin(value = "0.000", inclusive = false)
    @Column(name = "total_weight", precision = 12, scale = 3)
    private BigDecimal totalWeight;

    @Min(1)
    @Column(name = "package_count")
    private Integer packageCount;

    @Size(max = 128)
    @Column(name = "label_generation_id", length = 128)
    private String labelGenerationId;

    @Size(max = 150)
    @Column(name = "label_code", length = 150)
    private String labelCode;

    @Column(name = "label_generated_at")
    private Instant labelGeneratedAt;

    @Column(name = "label_printed_at")
    private Instant labelPrintedAt;

    @NotNull
    @Column(name = "started_by_user_id", nullable = false, updatable = false)
    private UUID startedByUserId;

    @Column(name = "finalized_by_user_id")
    private UUID finalizedByUserId;

    @NotNull
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Version
    @NotNull
    @PositiveOrZero
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
