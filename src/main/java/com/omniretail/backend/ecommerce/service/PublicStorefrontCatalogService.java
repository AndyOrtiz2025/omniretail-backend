package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.ecommerce.dto.PublicStorefrontProductResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecturas anónimas del catálogo publicado en la tienda de un tenant. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicStorefrontCatalogService {

    private final TenantRepository tenantRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;

    public List<PublicStorefrontProductResponse> listProducts(String slug) {
        UUID tenantId = resolveActiveTenant(slug).getId();
        Map<UUID, Category> activeCategories = categoryRepository
                .findByTenantIdAndStatus(tenantId, CategoryStatus.active).stream()
                .collect(java.util.stream.Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, Unit> units = unitRepository.findByTenantId(tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(Unit::getId, Function.identity()));

        return productRepository.findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published).stream()
                .map(product -> toResponse(product, activeCategories, units))
                .toList();
    }

    public PublicStorefrontProductResponse getProduct(String slug, UUID productId) {
        UUID tenantId = resolveActiveTenant(slug).getId();
        Product product = productRepository
                .findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(tenantId, productId, ProductStatus.published)
                .orElseThrow(() -> productNotFound());
        String categoryName = categoryRepository.findById(product.getCategoryId())
                .filter(found -> found.getTenantId().equals(tenantId) && found.getStatus() == CategoryStatus.active)
                .map(Category::getName)
                .orElse(null);
        UUID saleUnitId = product.getSaleUnitId() != null ? product.getSaleUnitId() : product.getBaseUnitId();
        String saleUnitName = unitRepository.findById(saleUnitId)
                .filter(found -> found.getTenantId().equals(tenantId))
                .map(Unit::getName)
                .orElse(null);
        return PublicStorefrontProductResponse.from(product, categoryName, saleUnitId, saleUnitName);
    }

    private PublicStorefrontProductResponse toResponse(
            Product product, Map<UUID, Category> categories, Map<UUID, Unit> units) {
        UUID saleUnitId = product.getSaleUnitId() != null ? product.getSaleUnitId() : product.getBaseUnitId();
        Unit saleUnit = units.get(saleUnitId);
        Category category = categories.get(product.getCategoryId());
        return PublicStorefrontProductResponse.from(
                product,
                category != null ? category.getName() : null,
                saleUnitId,
                saleUnit != null ? saleUnit.getName() : null);
    }

    private Tenant resolveActiveTenant(String slug) {
        return tenantRepository.findBySlug(slug)
                .filter(tenant -> tenant.getStatus() == TenantStatus.active)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "STOREFRONT_NOT_FOUND", "La tienda pública no está disponible."));
    }

    private BusinessException productNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
}
