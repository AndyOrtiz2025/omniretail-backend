package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.EcommerceConfig;
import java.util.List;
import java.util.UUID;

public record EcommerceConfigResponse(
        UUID tenantId,
        boolean enabled,
        String storeName,
        String logoUrl,
        String contactPhone,
        String contactEmail,
        boolean requireAccountForCheckout,
        boolean guestTrackingEnabled,
        List<String> allowedDeliveryMethods,
        List<String> allowedPaymentMethods,
        UUID defaultBranchId) {

    public static EcommerceConfigResponse from(EcommerceConfig config) {
        return new EcommerceConfigResponse(
                config.getTenantId(),
                config.isEnabled(),
                config.getStoreName(),
                config.getLogoUrl(),
                config.getContactPhone(),
                config.getContactEmail(),
                config.isRequireAccountForCheckout(),
                config.isGuestTrackingEnabled(),
                config.getAllowedDeliveryMethods(),
                config.getAllowedPaymentMethods(),
                config.getDefaultBranchId());
    }
}
