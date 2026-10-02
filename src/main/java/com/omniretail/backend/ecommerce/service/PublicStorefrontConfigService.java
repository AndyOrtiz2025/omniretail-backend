package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.dto.HeroBannerSlideDto;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.HeroBannerConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.ecommerce.dto.PublicStorefrontConfigResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Lee la configuracion visible de una tienda sin exponer datos administrativos. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicStorefrontConfigService {

    private static final TypeReference<List<HeroBannerSlideDto>> SLIDES_TYPE = new TypeReference<>() {};

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final HeroBannerConfigRepository heroBannerConfigRepository;
    private final JsonMapper jsonMapper;

    public PublicStorefrontConfigResponse getConfig(String slug) {
        Tenant tenant = tenantRepository.findBySlug(slug)
                .filter(found -> found.getStatus() == TenantStatus.active)
                .orElseThrow(this::storefrontNotFound);
        var config = ecommerceConfigRepository.findByTenantId(tenant.getId())
                .orElseThrow(this::storefrontNotFound);
        List<HeroBannerSlideDto> slides = heroBannerConfigRepository.findByTenantId(tenant.getId())
                .map(banner -> jsonMapper.readValue(banner.getSlides(), SLIDES_TYPE))
                .orElseGet(List::of);
        List<Branch> branches = branchRepository.findByTenantIdAndStatus(tenant.getId(), BranchStatus.active).stream()
                .filter(branch -> branch.getType() == BranchType.store)
                .toList();
        return PublicStorefrontConfigResponse.from(config, slides, branches);
    }

    private BusinessException storefrontNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "STOREFRONT_NOT_FOUND",
                "La tienda pública no está disponible.");
    }
}
