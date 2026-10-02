package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
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
@Table(name = "inventory_transfer_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryTransferRequest extends TenantScopedEntity {

    @NotNull
    @Column(name = "requesting_branch_id", nullable = false, updatable = false)
    private UUID requestingBranchId;

    @NotNull
    @Column(name = "source_branch_id", nullable = false, updatable = false)
    private UUID sourceBranchId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotNull
    @DecimalMin(value = "0.000", inclusive = false)
    @Column(
            name = "requested_quantity",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 3)
    private BigDecimal requestedQuantity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private InventoryTransferReason reason;

    @Size(max = 500)
    @Column(name = "notes", length = 500, updatable = false)
    private String notes;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryTransferRequestStatus status = InventoryTransferRequestStatus.requested;

    @NotNull
    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;

    @NotNull
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Size(max = 500)
    @Column(name = "review_notes", length = 500)
    private String reviewNotes;

    @Version
    @NotNull
    @PositiveOrZero
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
