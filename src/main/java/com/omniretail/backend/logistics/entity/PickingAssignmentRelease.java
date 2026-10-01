package com.omniretail.backend.logistics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** Evidencia append-only de una liberacion de asignacion de Picking. */
@Entity
@Table(name = "picking_assignment_releases")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickingAssignmentRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "picking_order_id", nullable = false, updatable = false)
    private UUID pickingOrderId;

    @NotNull
    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @NotBlank
    @Size(max = 500)
    @Column(name = "reason", nullable = false, updatable = false, length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "released_at", nullable = false, updatable = false)
    private Instant releasedAt;
}
