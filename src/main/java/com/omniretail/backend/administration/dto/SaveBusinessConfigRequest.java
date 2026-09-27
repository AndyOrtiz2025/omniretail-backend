package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BusinessPreset;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record SaveBusinessConfigRequest(
        @NotNull BusinessPreset preset,
        boolean supportsInventory,
        boolean supportsLots,
        boolean supportsExpiration,
        boolean supportsSerials,
        boolean supportsMultipleLocations,
        boolean supportsUnitsAndPackaging,
        boolean supportsProductAttributes,
        boolean supportsKits,
        boolean supportsServices,
        List<@NotNull @Pattern(regexp = "^(cash|card|transfer|mixed)$", message = "Método de pago no válido") String>
                allowedPosPaymentMethods,
        @NotNull @Valid ProductTrackingDto defaultProductTracking) {
}
