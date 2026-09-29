package com.omniretail.backend.purchasing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "purchase_order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @NotNull
    @Column(name = "supplier_product_id", nullable = false, updatable = false)
    private UUID supplierProductId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotNull
    @Column(name = "product_name_snapshot", nullable = false, length = 300)
    private String productNameSnapshot;

    @NotNull
    @Column(name = "product_sku_snapshot", nullable = false, length = 50)
    private String productSkuSnapshot;

    @Column(name = "supplier_sku_snapshot", length = 100)
    private String supplierSkuSnapshot;

    @NotNull
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @NotNull
    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @NotNull
    @Column(name = "unit_symbol_snapshot", nullable = false, length = 10)
    private String unitSymbolSnapshot;

    @NotNull
    @Column(name = "purchase_to_base_factor", nullable = false, precision = 18, scale = 6)
    private BigDecimal purchaseToBaseFactor;

    @NotNull
    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @NotNull
    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;
}
