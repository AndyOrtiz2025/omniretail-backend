package com.omniretail.backend.purchasing.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;

@Entity @Table(name = "purchase_order_items") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PurchaseOrderItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @NotNull @Column(name = "purchase_order_id", nullable = false, updatable = false) private UUID purchaseOrderId;
    @NotNull @Column(name = "product_id", nullable = false, updatable = false) private UUID productId;
    @NotNull @Column(name = "sku_snapshot", nullable = false, length = 100, updatable = false) private String skuSnapshot;
    @NotNull @Column(name = "name_snapshot", nullable = false, length = 300, updatable = false) private String nameSnapshot;
    @Column(name = "supplier_sku_snapshot", length = 100, updatable = false) private String supplierSkuSnapshot;
    @NotNull @Column(name = "purchase_unit_id", nullable = false, updatable = false) private UUID purchaseUnitId;
    @NotNull @Column(name = "purchase_to_base_factor", nullable = false, precision = 18, scale = 6, updatable = false) private BigDecimal purchaseToBaseFactor;
    @NotNull @Column(nullable = false, precision = 12, scale = 3) private BigDecimal quantity;
    @NotNull @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2) private BigDecimal unitCost;
    @NotNull @Column(nullable = false, precision = 12, scale = 2) private BigDecimal subtotal;
}
