package com.omniretail.backend.ecommerce.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Reserva de inventario asociada a una línea de una fuente de fulfillment. */
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
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private InventoryReservationSourceType sourceType;

    @NotNull
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @NotNull
    @Column(name = "source_line_id", nullable = false, updatable = false)
    private UUID sourceLineId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "order_item_id", updatable = false)
    private UUID orderItemId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotNull
    @Column(name = "quantity", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

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
