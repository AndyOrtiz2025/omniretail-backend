package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "inventory_transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryTransfer extends TenantScopedEntity {

    @NotBlank
    @Size(max = 50)
    @Column(name = "number", nullable = false, length = 50, updatable = false)
    private String number;

    @NotNull
    @Column(name = "source_branch_id", nullable = false, updatable = false)
    private UUID sourceBranchId;

    @NotNull
    @Column(name = "destination_branch_id", nullable = false, updatable = false)
    private UUID destinationBranchId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryTransferStatus status = InventoryTransferStatus.preparing;

    @NotBlank
    @Size(max = 128)
    @Column(name = "operation_id", nullable = false, length = 128, updatable = false)
    private String operationId;

    @NotBlank
    @Column(
            name = "operation_fingerprint",
            nullable = false,
            columnDefinition = "text",
            updatable = false)
    private String operationFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30, updatable = false)
    private InventoryTransferReason reason;

    @Size(max = 500)
    @Column(name = "notes", length = 500, updatable = false)
    private String notes;

    @NotNull
    @Column(name = "prepared_by_user_id", nullable = false, updatable = false)
    private UUID preparedByUserId;

    @NotNull
    @Column(name = "prepared_at", nullable = false, updatable = false)
    private Instant preparedAt;

    @Column(name = "dispatched_by_user_id")
    private UUID dispatchedByUserId;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "received_by_user_id")
    private UUID receivedByUserId;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Size(max = 500)
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Version
    @NotNull
    @PositiveOrZero
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
