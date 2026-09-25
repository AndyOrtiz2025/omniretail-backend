package com.omniretail.backend.ecommerce.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Reserva de inventario de una línea de pedido; sus asignaciones se materializan en fase posterior. */
@Entity
@Table(name = "inventory_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryReservation extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @NotNull
    @Column(name = "order_item_id", nullable = false, updatable = false)
    private UUID orderItemId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InventoryReservationStatus status = InventoryReservationStatus.active;

    /** JSONB temporal para no acoplar esta migración a las tablas de balances de Inventario. */
    @NotNull
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allocations", nullable = false, columnDefinition = "jsonb")
    private String allocations = "[]";
}
