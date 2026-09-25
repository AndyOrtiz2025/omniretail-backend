package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends TenantScopedEntity {

    @NotBlank
    @Size(max = 50)
    @Column(name = "sku", nullable = false)
    private String sku;

    @Size(max = 50)
    @Column(name = "barcode")
    private String barcode;

    @NotBlank
    @Size(max = 300)
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Size(max = 100)
    @Column(name = "brand")
    private String brand;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private ProductType productType = ProductType.physical;

    @NotNull
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @NotNull
    @Column(name = "base_unit_id", nullable = false)
    private UUID baseUnitId;

    @Column(name = "inventory_unit_id")
    private UUID inventoryUnitId;

    @Column(name = "sale_unit_id")
    private UUID saleUnitId;

    @NotNull
    @Builder.Default
    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice = BigDecimal.ZERO;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductStatus status = ProductStatus.published;

    @NotNull
    @Builder.Default
    @Column(name = "tracking_stock", nullable = false)
    private Boolean trackingStock = true;

    @NotNull
    @Builder.Default
    @Column(name = "tracking_lot", nullable = false)
    private Boolean trackingLot = false;

    @NotNull
    @Builder.Default
    @Column(name = "tracking_expiration", nullable = false)
    private Boolean trackingExpiration = false;

    @NotNull
    @Builder.Default
    @Column(name = "tracking_serial", nullable = false)
    private Boolean trackingSerial = false;

    @NotNull
    @Builder.Default
    @Column(name = "channel_ecommerce", nullable = false)
    private Boolean channelEcommerce = true;

    @NotNull
    @Builder.Default
    @Column(name = "channel_pos", nullable = false)
    private Boolean channelPos = true;

    @NotNull
    @Builder.Default
    @Column(name = "channel_mobile_app", nullable = false)
    private Boolean channelMobileApp = false;
}
