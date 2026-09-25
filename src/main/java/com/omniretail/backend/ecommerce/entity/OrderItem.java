package com.omniretail.backend.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Línea histórica de pedido. Los snapshots comerciales nunca se recalculan. */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotBlank
    @Size(max = 50)
    @Column(name = "sku_snapshot", nullable = false, updatable = false)
    private String skuSnapshot;

    @NotBlank
    @Size(max = 300)
    @Column(name = "name_snapshot", nullable = false, updatable = false)
    private String nameSnapshot;

    @NotNull
    @DecimalMin(value = "0.000", inclusive = false)
    @Column(name = "quantity", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @DecimalMin(value = "0.000", inclusive = false)
    @Column(name = "inventory_quantity", updatable = false, precision = 12, scale = 3)
    private BigDecimal inventoryQuantity;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "unit_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "discount", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal discount;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "subtotal", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fulfillment_components", columnDefinition = "jsonb", updatable = false)
    private String fulfillmentComponents;
}
