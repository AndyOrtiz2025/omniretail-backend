package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.administration.dto.HeroBannerSlideDto;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import java.util.List;
import java.util.UUID;

/** Configuracion comercial segura para una tienda publica. */
public record PublicStorefrontConfigResponse(
        UUID tenantId,
        boolean enabled,
        String storeName,
        String logoUrl,
        String contactPhone,
        String contactEmail,
        boolean requireAccountForCheckout,
        boolean guestTrackingEnabled,
        List<HeroBannerSlideDto> slides,
        List<PublicStorefrontBranchResponse> branches) {

    public static PublicStorefrontConfigResponse from(
            EcommerceConfig config, List<HeroBannerSlideDto> slides, List<Branch> branches) {
        return new PublicStorefrontConfigResponse(
                config.getTenantId(),
                config.isEnabled(),
                config.getStoreName(),
                config.getLogoUrl(),
                config.getContactPhone(),
                config.getContactEmail(),
                config.isRequireAccountForCheckout(),
                config.isGuestTrackingEnabled(),
                slides,
                branches.stream().map(PublicStorefrontBranchResponse::from).toList());
    }
}
