package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ProductMediaCreateRequest;
import com.omniretail.backend.catalog.dto.ProductMediaResponse;
import com.omniretail.backend.catalog.dto.ProductMediaUpdateRequest;
import com.omniretail.backend.catalog.entity.ProductMedia;
import com.omniretail.backend.catalog.entity.ProductMediaType;
import com.omniretail.backend.catalog.repository.ProductMediaRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.media.MediaStorageService;
import com.omniretail.backend.shared.security.CurrentUser;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ProductMediaService {
    private static final long MAX_MEDIA = 6;
    private final ProductRepository productRepository;
    private final ProductMediaRepository repository;
    private final MediaStorageService storage;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<ProductMediaResponse> list(UUID productId) {
        UUID tenantId = currentUser.require().tenantId();
        requireProduct(tenantId, productId);
        return responses(tenantId, productId);
    }

    @Transactional
    public ProductMediaResponse createExternal(UUID productId, ProductMediaCreateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        requireProductForUpdate(tenantId, productId); requireCapacity(tenantId, productId); requireExternalUrl(request.url());
        return saveNew(tenantId, productId, request.type(), request.url().trim(), request.altText(),
                request.sortOrder(), Boolean.TRUE.equals(request.primary()));
    }

    @Transactional
    public ProductMediaResponse upload(UUID productId, MultipartFile file, String altText,
            Integer sortOrder, Boolean primary) {
        UUID tenantId = currentUser.require().tenantId();
        requireProductForUpdate(tenantId, productId); requireCapacity(tenantId, productId);
        requireSortOrder(sortOrder);
        String url = storage.storeImage(tenantId, "products", productId, file);
        try {
            return saveNew(tenantId, productId, ProductMediaType.image, url, altText, sortOrder,
                    Boolean.TRUE.equals(primary));
        } catch (RuntimeException exception) {
            storage.deleteQuietly(url); throw exception;
        }
    }

    @Transactional
    public ProductMediaResponse update(UUID productId, UUID mediaId, ProductMediaUpdateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        requireProductForUpdate(tenantId, productId);
        ProductMedia media = requireMedia(tenantId, productId, mediaId);
        if (request.url() != null) {
            if (storage.isManaged(media.getUrl())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_MEDIA_LOCAL_URL_IMMUTABLE",
                        "Reemplaza una imagen local mediante un nuevo upload.");
            }
            requireExternalUrl(request.url());
            media.setUrl(request.url().trim());
        }
        media.setAltText(request.altText());
        requireSortOrder(request.sortOrder());
        if (request.sortOrder() != null) media.setSortOrder(request.sortOrder());
        ProductMedia saved = repository.saveAndFlush(media);
        return ProductMediaResponse.from(normalizeSortOrder(tenantId, productId, saved));
    }

    @Transactional
    public void delete(UUID productId, UUID mediaId) {
        UUID tenantId = currentUser.require().tenantId();
        requireProductForUpdate(tenantId, productId);
        ProductMedia media = requireMedia(tenantId, productId, mediaId);
        String url = media.getUrl();
        repository.delete(media); repository.flush();
        normalizeSortOrder(tenantId, productId, null);
        storage.deleteAfterCommit(url);
    }

    @Transactional
    public ProductMediaResponse setPrimary(UUID productId, UUID mediaId) {
        UUID tenantId = currentUser.require().tenantId();
        requireProductForUpdate(tenantId, productId);
        ProductMedia media = requireMedia(tenantId, productId, mediaId);
        if (Boolean.TRUE.equals(media.getPrimary())) return ProductMediaResponse.from(media);
        repository.clearPrimary(tenantId, productId);
        media.setPrimary(true);
        ProductMedia saved = repository.saveAndFlush(media);
        return ProductMediaResponse.from(normalizeSortOrder(tenantId, productId, saved));
    }

    private ProductMediaResponse saveNew(UUID tenantId, UUID productId, ProductMediaType type, String url,
            String altText, Integer sortOrder, boolean primary) {
        requireSortOrder(sortOrder);
        if (primary) repository.clearPrimary(tenantId, productId);
        ProductMedia media = ProductMedia.builder().productId(productId).type(type).url(url)
                .altText(altText).sortOrder(sortOrder == null ? nextSort(tenantId, productId) : sortOrder)
                .primary(primary).build();
        media.setTenantId(tenantId);
        ProductMedia saved = repository.saveAndFlush(media);
        return ProductMediaResponse.from(normalizeSortOrder(tenantId, productId, saved));
    }

    private int nextSort(UUID tenantId, UUID productId) {
        return repository.findByTenantIdAndProductIdOrderBySortOrderAscIdAsc(tenantId, productId).stream()
                .map(ProductMedia::getSortOrder).max(Integer::compareTo).orElse(-1) + 1;
    }
    private void requireCapacity(UUID tenantId, UUID productId) {
        if (repository.countByTenantIdAndProductId(tenantId, productId) >= MAX_MEDIA)
            throw BusinessException.conflict("PRODUCT_MEDIA_LIMIT", "El producto admite un maximo de 6 elementos multimedia.");
    }
    private void requireProduct(UUID tenantId, UUID id) {
        if (productRepository.findByTenantIdAndId(tenantId, id).isEmpty())
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
    private void requireProductForUpdate(UUID tenantId, UUID id) {
        if (productRepository.findForUpdateByTenantIdAndId(tenantId, id).isEmpty())
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
    private ProductMedia requireMedia(UUID tenantId, UUID productId, UUID mediaId) {
        return repository.findByTenantIdAndProductIdAndId(tenantId, productId, mediaId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_MEDIA_NOT_FOUND", "Media de producto no encontrada."));
    }
    private List<ProductMediaResponse> responses(UUID tenantId, UUID productId) {
        return repository.findByTenantIdAndProductIdOrderBySortOrderAscIdAsc(tenantId, productId)
                .stream().map(ProductMediaResponse::from).toList();
    }
    private ProductMedia normalizeSortOrder(UUID tenantId, UUID productId, ProductMedia target) {
        List<ProductMedia> values = repository.findByTenantIdAndProductIdOrderBySortOrderAscIdAsc(tenantId, productId);
        for (int index = 0; index < values.size(); index++) values.get(index).setSortOrder(index);
        if (!values.isEmpty()) repository.saveAllAndFlush(values);
        if (target == null) return null;
        return values.stream().filter(value -> java.util.Objects.equals(value.getId(), target.getId()))
                .findFirst().orElse(target);
    }
    private static void requireExternalUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || host == null || "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host) || "::1".equals(host)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_MEDIA_URL_INVALID", "La URL externa no es valida.");
        }
    }
    private static void requireSortOrder(Integer value) {
        if (value != null && value < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_MEDIA_SORT_INVALID",
                    "El orden de la media no puede ser negativo.");
        }
    }
}
