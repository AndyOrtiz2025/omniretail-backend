package com.omniretail.backend.pos.entity;

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

/** Linea historica de una venta; sus valores y snapshots no se actualizan despues de crearla. */
@Entity
@Table(name = "sale_items")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

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
}
