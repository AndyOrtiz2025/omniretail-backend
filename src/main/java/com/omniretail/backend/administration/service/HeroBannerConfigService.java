package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.HeroBannerConfigResponse;
import com.omniretail.backend.administration.dto.HeroBannerSlideDto;
import com.omniretail.backend.administration.dto.SaveHeroBannerConfigRequest;
import com.omniretail.backend.administration.entity.HeroBannerConfig;
import com.omniretail.backend.administration.repository.HeroBannerConfigRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
@RequiredArgsConstructor
public class HeroBannerConfigService {

    private static final TypeReference<List<HeroBannerSlideDto>> SLIDES_TYPE = new TypeReference<>() {};

    private final HeroBannerConfigRepository configRepository;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public HeroBannerConfigResponse getBanner() {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);
        List<HeroBannerSlideDto> slides = configRepository
                .findByTenantId(tenantId)
                .map(config -> jsonMapper.readValue(config.getSlides(), SLIDES_TYPE))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "HERO_BANNER_NOT_FOUND",
                        "No hay un carrusel configurado para el negocio actual."));
        return new HeroBannerConfigResponse(slides);
    }

    public HeroBannerConfigResponse saveBanner(SaveHeroBannerConfigRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);

        List<HeroBannerSlideDto> slides = request.slides().stream()
                .map(HeroBannerConfigService::normalizeSlide)
                .toList();

        HeroBannerConfig config = configRepository.findByTenantId(tenantId).orElseGet(() -> {
            HeroBannerConfig created = new HeroBannerConfig();
            created.setTenantId(tenantId);
            return created;
        });
        config.setSlides(jsonMapper.writeValueAsString(slides));
        configRepository.save(config);

        return new HeroBannerConfigResponse(slides);
    }

    private static HeroBannerSlideDto normalizeSlide(HeroBannerSlideDto slide) {
        String imageUrl = slide.imageUrl();
        return new HeroBannerSlideDto(
                slide.title() != null ? slide.title().trim() : "",
                slide.description() != null ? slide.description().trim() : "",
                imageUrl != null && !imageUrl.isBlank() ? imageUrl.trim() : null);
    }
}
