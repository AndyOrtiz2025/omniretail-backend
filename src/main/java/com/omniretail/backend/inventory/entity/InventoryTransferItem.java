package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "inventory_transfer_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryTransferItem extends TenantScopedEntity {

    @NotNull
    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "source_request_id", updatable = false)
    private UUID sourceRequestId;

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
    @Column(name = "dispatched_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal dispatchedQuantity = BigDecimal.ZERO;

    @NotNull
    @Builder.Default
    @DecimalMin("0.000")
    @Column(name = "received_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;
}
