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
@Table(name = "inventory_transfer_receipt_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryTransferReceiptItem extends TenantScopedEntity {

    @NotNull
    @Column(name = "receipt_id", nullable = false, updatable = false)
    private UUID receiptId;

    @NotNull
    @Column(name = "transfer_item_id", nullable = false, updatable = false)
    private UUID transferItemId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotNull
    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @NotNull
    @DecimalMin(value = "0.000", inclusive = false)
    @Column(name = "quantity", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;
}
