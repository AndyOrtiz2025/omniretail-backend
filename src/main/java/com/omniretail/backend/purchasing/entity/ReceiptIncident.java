package com.omniretail.backend.purchasing.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "receipt_incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptIncident extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private UUID goodsReceiptId;

    @Column(name = "goods_receipt_item_id", updatable = false)
    private UUID goodsReceiptItemId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 20, updatable = false)
    private ReceiptIncidentType incidentType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReceiptIncidentStatus status;

    @Column(name = "quantity_affected", precision = 12, scale = 3, updatable = false)
    private BigDecimal quantityAffected;

    @NotNull
    @Column(name = "notes", nullable = false, length = 1000, updatable = false)
    private String notes;

    @NotNull
    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
