package com.omniretail.backend.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.omniretail.backend.catalog.entity.ProductType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ProductUpdateRequest(
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
        @NotNull @Valid ProductTrackingDto tracking,
        @NotNull @Valid ProductChannelsDto channels) {

    @JsonIgnore
    @AssertTrue(message = "El control por fecha de vencimiento requiere control por lote.")
    public boolean isLotExpirationConfigurationValid() {
        return tracking == null
                || !Boolean.TRUE.equals(tracking.expiration())
                || Boolean.TRUE.equals(tracking.lot());
    }
}
