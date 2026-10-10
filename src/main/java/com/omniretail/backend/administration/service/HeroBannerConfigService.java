package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.HeroBannerConfigResponse;
import com.omniretail.backend.administration.dto.HeroBannerSlideDto;
import com.omniretail.backend.administration.dto.SaveHeroBannerConfigRequest;
import com.omniretail.backend.administration.entity.HeroBannerConfig;
import com.omniretail.backend.administration.repository.HeroBannerConfigRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.media.MediaStorageService;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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
    private final MediaStorageService mediaStorageService;

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
        List<HeroBannerSlideDto> previousSlides = readSlides(config);
        config.setSlides(jsonMapper.writeValueAsString(slides));
        configRepository.save(config);

        // Las imagenes gestionadas que ya no estan en ninguna diapositiva se borran al confirmar.
        for (HeroBannerSlideDto previous : previousSlides) {
            String previousUrl = previous.imageUrl();
            boolean stillUsed = slides.stream().anyMatch(slide -> Objects.equals(slide.imageUrl(), previousUrl));
            if (!stillUsed) {
                mediaStorageService.deleteAfterCommit(previousUrl);
            }
        }
        return new HeroBannerConfigResponse(slides);
    }

    /**
     * Sube la imagen de una diapositiva (0 a 2) desde el equipo. El carrusel debe existir. Reemplaza la
     * imagen anterior y borra su archivo gestionado al confirmar.
     */
    public HeroBannerConfigResponse uploadSlideImage(int index, MultipartFile file) {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);
        HeroBannerConfig config = requireConfig(tenantId);
        List<HeroBannerSlideDto> slides = new ArrayList<>(readSlides(config));
        ensureSlideIndex(slides, index);

        String newUrl = mediaStorageService.storeImage(
                tenantId, MediaStorageService.SCOPE_ECOMMERCE, tenantId, file);
        HeroBannerSlideDto current = slides.get(index);
        try {
            slides.set(index, new HeroBannerSlideDto(current.title(), current.description(), newUrl));
            config.setSlides(jsonMapper.writeValueAsString(slides));
            configRepository.saveAndFlush(config);
            mediaStorageService.deleteAfterCommit(current.imageUrl());
            return new HeroBannerConfigResponse(List.copyOf(slides));
        } catch (RuntimeException exception) {
            mediaStorageService.deleteQuietly(newUrl);
            throw exception;
        }
    }

    /** Quita la imagen de una diapositiva y borra su archivo gestionado al confirmar. */
    public HeroBannerConfigResponse deleteSlideImage(int index) {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);
        HeroBannerConfig config = requireConfig(tenantId);
        List<HeroBannerSlideDto> slides = new ArrayList<>(readSlides(config));
        ensureSlideIndex(slides, index);

        HeroBannerSlideDto current = slides.get(index);
        slides.set(index, new HeroBannerSlideDto(current.title(), current.description(), null));
        config.setSlides(jsonMapper.writeValueAsString(slides));
        configRepository.saveAndFlush(config);
        mediaStorageService.deleteAfterCommit(current.imageUrl());
        return new HeroBannerConfigResponse(List.copyOf(slides));
    }

    private HeroBannerConfig requireConfig(UUID tenantId) {
        return configRepository
                .findByTenantId(tenantId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "HERO_BANNER_NOT_FOUND",
                        "No hay un carrusel configurado para el negocio actual."));
    }

    private List<HeroBannerSlideDto> readSlides(HeroBannerConfig config) {
        List<HeroBannerSlideDto> slides = jsonMapper.readValue(config.getSlides(), SLIDES_TYPE);
        return slides != null ? slides : List.of();
    }

    private static void ensureSlideIndex(List<HeroBannerSlideDto> slides, int index) {
        if (index < 0 || index >= slides.size()) {
            throw BusinessException.badRequest("La diapositiva indicada no existe.");
        }
    }

    private static HeroBannerSlideDto normalizeSlide(HeroBannerSlideDto slide) {
        String imageUrl = slide.imageUrl();
        return new HeroBannerSlideDto(
                slide.title() != null ? slide.title().trim() : "",
                slide.description() != null ? slide.description().trim() : "",
                imageUrl != null && !imageUrl.isBlank() ? imageUrl.trim() : null);
    }
}
