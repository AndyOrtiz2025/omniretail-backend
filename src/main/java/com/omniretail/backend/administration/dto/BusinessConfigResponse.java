package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BusinessCapabilitiesConfig;
import com.omniretail.backend.administration.entity.BusinessPreset;
import java.util.List;
import java.util.UUID;

public record BusinessConfigResponse(
        UUID tenantId,
        BusinessPreset preset,
        boolean supportsInventory,
        boolean supportsLots,
        boolean supportsExpiration,
        boolean supportsSerials,
        boolean supportsMultipleLocations,
        boolean supportsUnitsAndPackaging,
        boolean supportsProductAttributes,
        boolean supportsKits,
        boolean supportsServices,
        List<String> allowedPosPaymentMethods,
        ProductTrackingDto defaultProductTracking) {

    public static BusinessConfigResponse from(BusinessCapabilitiesConfig entity) {
        return new BusinessConfigResponse(
                entity.getTenantId(),
                entity.getPreset(),
                entity.isSupportsInventory(),
                entity.isSupportsLots(),
                entity.isSupportsExpiration(),
                entity.isSupportsSerials(),
                entity.isSupportsMultipleLocations(),
                entity.isSupportsUnitsAndPackaging(),
                entity.isSupportsProductAttributes(),
                entity.isSupportsKits(),
                entity.isSupportsServices(),
                entity.getAllowedPosPaymentMethods(),
                new ProductTrackingDto(
                        entity.isTrackStock(),
                        entity.isTrackLot(),
                        entity.isTrackExpiration(),
                        entity.isTrackSerial()));
    }
}
