package com.omniretail.backend.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductCreateRequest(
        @NotBlank @Size(max = 50) String sku,
        @Size(max = 50) String barcode,
        @NotBlank @Size(max = 300) String name,
        String description,
        @Size(max = 100) String brand,
        @NotNull ProductType productType,
        @NotNull UUID categoryId,
        @NotNull UUID baseUnitId,
        UUID inventoryUnitId,
        @NotNull UUID saleUnitId,
        @NotNull @DecimalMin("0.00") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2)
                BigDecimal salePrice,
        @NotNull ProductStatus status,
        @NotNull @Valid ProductTrackingDto tracking,
        @NotNull @Valid ProductChannelsDto channels) {

    @JsonIgnore
    @AssertTrue(message = "Lotes y fecha de vencimiento deben estar activados o desactivados juntos.")
    public boolean isLotExpirationConfigurationValid() {
        return tracking == null
                || tracking.lot() == null
                || tracking.expiration() == null
                || tracking.lot().equals(tracking.expiration());
    }
}
