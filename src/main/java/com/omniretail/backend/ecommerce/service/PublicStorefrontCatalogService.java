package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductMedia;
import com.omniretail.backend.catalog.entity.ProductMediaType;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductMediaRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.ecommerce.dto.PublicStorefrontProductResponse;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.time.Instant;
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
    private final ProductMediaRepository productMediaRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final ProductPriceResolver productPriceResolver;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;

    public List<PublicStorefrontProductResponse> listProducts(String slug) {
        UUID tenantId = resolveActiveTenant(slug).getId();
        Map<UUID, Category> activeCategories = categoryRepository
                .findByTenantIdAndStatus(tenantId, CategoryStatus.active).stream()
                .collect(java.util.stream.Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, Unit> units = unitRepository.findByTenantId(tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(Unit::getId, Function.identity()));
        Instant pricingAt = Instant.now();
        Map<UUID, BigDecimal> availableByProduct = availableByProduct(tenantId);
        List<Product> products = productRepository
                .findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published);
        Map<UUID, ProductMedia> primaryMedia = primaryMediaByProduct(tenantId, products);

        return products.stream()
                .map(product -> toResponse(
                        product, activeCategories, units, tenantId, pricingAt,
                        availableByProduct, primaryMedia.get(product.getId())))
                .toList();
    }

    public PublicStorefrontProductResponse getProduct(String slug, UUID productId) {
        UUID tenantId = resolveActiveTenant(slug).getId();
        Product product = productRepository
                .findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(tenantId, productId, ProductStatus.published)
                .orElseThrow(() -> productNotFound());
        Category category = categoryRepository.findById(product.getCategoryId())
                .filter(found -> found.getTenantId().equals(tenantId) && found.getStatus() == CategoryStatus.active)
                .orElse(null);
        UUID saleUnitId = product.getSaleUnitId() != null ? product.getSaleUnitId() : product.getBaseUnitId();
        String saleUnitName = unitRepository.findById(saleUnitId)
                .filter(found -> found.getTenantId().equals(tenantId))
                .map(Unit::getName)
                .orElse(null);
        StockAvailability stock = stockAvailability(product, availableByProduct(tenantId));
        ProductMedia primaryMedia = primaryMediaByProduct(tenantId, List.of(product)).get(product.getId());
        return PublicStorefrontProductResponse.from(
                product,
                category != null ? category.getName() : null,
                category != null ? category.getImageUrl() : null,
                saleUnitId,
                saleUnitName,
                productPriceResolver.resolveEffectivePrice(tenantId, product, Instant.now(), "ecommerce", null),
                primaryMedia != null ? primaryMedia.getUrl() : null,
                primaryMedia != null ? primaryMedia.getAltText() : null,
                stock.inStock(),
                stock.availableQuantity());
    }

    private PublicStorefrontProductResponse toResponse(
            Product product,
            Map<UUID, Category> categories,
            Map<UUID, Unit> units,
            UUID tenantId,
            Instant pricingAt,
            Map<UUID, BigDecimal> availableByProduct,
            ProductMedia primaryMedia) {
        UUID saleUnitId = product.getSaleUnitId() != null ? product.getSaleUnitId() : product.getBaseUnitId();
        Unit saleUnit = units.get(saleUnitId);
        Category category = categories.get(product.getCategoryId());
        StockAvailability stock = stockAvailability(product, availableByProduct);
        return PublicStorefrontProductResponse.from(
                product,
                category != null ? category.getName() : null,
                category != null ? category.getImageUrl() : null,
                saleUnitId,
                saleUnit != null ? saleUnit.getName() : null,
                productPriceResolver.resolveEffectivePrice(
                        tenantId, product, pricingAt, "ecommerce", null),
                primaryMedia != null ? primaryMedia.getUrl() : null,
                primaryMedia != null ? primaryMedia.getAltText() : null,
                stock.inStock(),
                stock.availableQuantity());
    }

    /** Carga en lote una sola imagen principal por producto para evitar consultas N+1. */
    private Map<UUID, ProductMedia> primaryMediaByProduct(UUID tenantId, List<Product> products) {
        if (products.isEmpty()) return Map.of();
        Map<UUID, ProductMedia> primaryMedia = new LinkedHashMap<>();
        productMediaRepository
                .findByTenantIdAndProductIdInAndPrimaryTrueAndTypeOrderByProductIdAscSortOrderAscIdAsc(
                        tenantId,
                        products.stream().map(Product::getId).toList(),
                        ProductMediaType.image)
                .forEach(media -> primaryMedia.putIfAbsent(media.getProductId(), media));
        return primaryMedia;
    }

    /**
     * Disponible (cantidad - reservado) por producto en la sucursal que atiende el e-commerce,
     * la misma contra la que el checkout reserva stock. Sin sucursal configurada no hay stock.
     */
    private Map<UUID, BigDecimal> availableByProduct(UUID tenantId) {
        return ecommerceConfigRepository.findByTenantId(tenantId)
                .map(EcommerceConfig::getDefaultBranchId)
                .map(branchId -> inventoryBalanceRepository
                        .findByTenantIdAndBranchIdAndLocationIdIsNull(tenantId, branchId).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                InventoryBalance::getProductId,
                                balance -> balance.getQuantity().subtract(balance.getReservedQuantity()))))
                .orElse(Map.of());
    }

    private static StockAvailability stockAvailability(Product product, Map<UUID, BigDecimal> availableByProduct) {
        if (!Boolean.TRUE.equals(product.getTrackingStock())) {
            return new StockAvailability(true, null);
        }
        BigDecimal available = availableByProduct.getOrDefault(product.getId(), BigDecimal.ZERO);
        return new StockAvailability(available.signum() > 0, available.max(BigDecimal.ZERO));
    }

    private record StockAvailability(boolean inStock, BigDecimal availableQuantity) {}

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
