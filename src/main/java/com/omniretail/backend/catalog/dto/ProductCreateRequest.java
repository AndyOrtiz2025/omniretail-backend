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
        UUID saleUnitId,
        @NotNull @DecimalMin("0.00") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2)
                BigDecimal salePrice,
        @NotNull ProductStatus status,
        @NotNull @Valid ProductTrackingDto tracking,
        @NotNull @Valid ProductChannelsDto channels) {

    @JsonIgnore
    @AssertTrue(message = "El control por fecha de vencimiento requiere que el control por lote este activado.")
    public boolean isLotExpirationConfigurationValid() {
        if (tracking == null) {
            return true;
        }
        return !Boolean.TRUE.equals(tracking.expiration()) || Boolean.TRUE.equals(tracking.lot());
    }
}
