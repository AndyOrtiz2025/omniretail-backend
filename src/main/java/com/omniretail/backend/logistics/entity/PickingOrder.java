package com.omniretail.backend.logistics.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "picking_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickingOrder extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private PickingSourceType sourceType;

    @NotNull
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PickingStatus status = PickingStatus.pending;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private PickingPriority priority = PickingPriority.normal;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
