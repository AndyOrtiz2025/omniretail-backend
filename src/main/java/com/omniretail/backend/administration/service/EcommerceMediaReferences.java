package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.HeroBannerSlideDto;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.HeroBannerConfigRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.media.MediaStorageService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reglas de seguridad sobre los archivos que usa la tienda en linea (logo y carrusel).
 *
 * <p>Las URLs de logo y diapositivas las escribe el administrador y son texto libre, asi que nunca
 * se puede confiar en que apunten a un archivo propio. Por eso:
 *
 * <ul>
 *   <li>una URL de {@code /media} de OTRO negocio se rechaza al guardar;
 *   <li>solo se borran archivos de la zona {@code ecommerce} del negocio activo: una foto de
 *       producto o de categoria (propia o ajena) puesta como logo nunca se borra desde aqui;
 *   <li>un archivo solo se borra si ya no lo usa ninguna otra referencia de la tienda (el logo o
 *       cualquier diapositiva).
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EcommerceMediaReferences {

    private static final TypeReference<List<HeroBannerSlideDto>> SLIDES_TYPE = new TypeReference<>() {};

    private final MediaStorageService mediaStorageService;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final HeroBannerConfigRepository heroBannerConfigRepository;
    private final JsonMapper jsonMapper;

    /** Rechaza una URL gestionada ({@code /media/...}) que pertenezca a otro negocio. */
    public void requireNotForeignMedia(UUID tenantId, String url) {
        if (mediaStorageService.isManaged(url) && !mediaStorageService.isManagedByTenant(url, tenantId)) {
            throw BusinessException.badRequest("La imagen indicada no pertenece al negocio activo.");
        }
    }

    /**
     * Programa el borrado (al confirmar la transaccion) de un archivo que dejo de usarse. Se llama
     * despues de guardar el cambio: la comprobacion de referencias ve el estado ya modificado.
     */
    public void deleteAfterCommitIfUnreferenced(UUID tenantId, String previousUrl) {
        if (!mediaStorageService.isManagedIn(previousUrl, tenantId, MediaStorageService.SCOPE_ECOMMERCE)) {
            return;
        }
        if (isReferenced(tenantId, previousUrl)) {
            return;
        }
        mediaStorageService.deleteAfterCommit(previousUrl);
    }

    private boolean isReferenced(UUID tenantId, String url) {
        boolean usedAsLogo = ecommerceConfigRepository
                .findByTenantId(tenantId)
                .map(config -> Objects.equals(config.getLogoUrl(), url))
                .orElse(false);
        if (usedAsLogo) {
            return true;
        }
        return heroBannerConfigRepository
                .findByTenantId(tenantId)
                .map(config -> {
                    List<HeroBannerSlideDto> slides = jsonMapper.readValue(config.getSlides(), SLIDES_TYPE);
                    return slides != null && slides.stream().anyMatch(slide -> Objects.equals(slide.imageUrl(), url));
                })
                .orElse(false);
    }
}
