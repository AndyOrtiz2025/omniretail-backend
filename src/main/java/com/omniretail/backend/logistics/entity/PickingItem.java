package com.omniretail.backend.logistics.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
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

@Entity
@Table(name = "picking_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickingItem extends TenantScopedEntity {

    @NotNull
    @Column(name = "picking_order_id", nullable = false, updatable = false)
    private UUID pickingOrderId;

    @NotNull
    @Column(name = "source_line_id", nullable = false, updatable = false)
    private UUID sourceLineId;

    @Column(name = "order_item_id", updatable = false)
    private UUID orderItemId;

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
    @Builder.Default
    @DecimalMin("0.000")
    @Column(name = "picked_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal pickedQuantity = BigDecimal.ZERO;

    @Column(name = "location_id")
    private UUID locationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "picked_traces", columnDefinition = "jsonb")
    private String pickedTraces;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PickingItemStatus status = PickingItemStatus.pending;
}
