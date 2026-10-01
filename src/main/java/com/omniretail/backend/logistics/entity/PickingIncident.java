package com.omniretail.backend.logistics.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
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

@Entity
@Table(name = "picking_incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickingIncident extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "picking_order_id", nullable = false, updatable = false)
    private UUID pickingOrderId;

    @Column(name = "picking_item_id", updatable = false)
    private UUID pickingItemId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, updatable = false, length = 30)
    private PickingIncidentType incidentType;

    @DecimalMin(value = "0.000", inclusive = false)
    @Column(name = "quantity_affected", updatable = false, precision = 12, scale = 3)
    private BigDecimal quantityAffected;

    @NotBlank
    @Size(max = 500)
    @Column(name = "comment", nullable = false, updatable = false, length = 500)
    private String comment;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PickingIncidentStatus status = PickingIncidentStatus.open;

    @NotNull
    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
