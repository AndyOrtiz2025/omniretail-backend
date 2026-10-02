package com.omniretail.backend.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** Desglose fisico historico de un InventoryMovement; Foundation Phase 1 no lo alimenta aun. */
@Entity
@Table(name = "inventory_movement_traces")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryMovementTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "movement_id", nullable = false, updatable = false)
    private UUID movementId;

    @Column(name = "lot_id", updatable = false)
    private UUID lotId;

    @Column(name = "serial_id", updatable = false)
    private UUID serialId;

    @NotNull
    @Positive
    @Column(name = "quantity", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
