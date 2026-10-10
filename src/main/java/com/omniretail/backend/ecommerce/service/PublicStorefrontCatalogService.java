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
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitConversion;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductKitComponentRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductMediaRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.ecommerce.dto.PublicStorefrontProductResponse;
import com.omniretail.backend.inventory.service.InventoryOperationalLocationService;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
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
    private final ProductKitComponentRepository productKitComponentRepository;
    private final ProductMediaRepository productMediaRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final UnitConversionRepository unitConversionRepository;
    private final ProductPriceResolver productPriceResolver;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final InventoryOperationalLocationService inventoryOperationalLocationService;

    public List<PublicStorefrontProductResponse> listProducts(String slug) {
        UUID tenantId = resolveActiveTenant(slug).getId();
        Map<UUID, Category> activeCategories = categoryRepository
                .findByTenantIdAndStatus(tenantId, CategoryStatus.active).stream()
                .collect(java.util.stream.Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, Unit> units = unitRepository.findByTenantId(tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(Unit::getId, Function.identity()));
        List<Product> products = productRepository
                .findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published);
        Instant pricingAt = Instant.now();
        Map<UUID, BigDecimal> availableByProduct = availableByProduct(tenantId);
        Map<UUID, BigDecimal> availableKits = availableKits(tenantId, products, availableByProduct);
        Map<UUID, BigDecimal> saleUnitFactors = saleUnitFactors(tenantId, products);
        Map<UUID, ProductMedia> primaryMedia = primaryMediaByProduct(tenantId, products);

        return products.stream()
                .map(product -> toResponse(
                        product, activeCategories, units, tenantId, pricingAt,
                        availableByProduct, availableKits, saleUnitFactors, primaryMedia.get(product.getId())))
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
        Map<UUID, BigDecimal> availableByProduct = availableByProduct(tenantId);
        StockAvailability stock = stockAvailability(
                product,
                availableByProduct,
                availableKits(tenantId, List.of(product), availableByProduct),
                saleUnitFactors(tenantId, List.of(product)));
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
            Map<UUID, BigDecimal> availableKits,
            Map<UUID, BigDecimal> saleUnitFactors,
            ProductMedia primaryMedia) {
        UUID saleUnitId = product.getSaleUnitId() != null ? product.getSaleUnitId() : product.getBaseUnitId();
        Unit saleUnit = units.get(saleUnitId);
        Category category = categories.get(product.getCategoryId());
        StockAvailability stock = stockAvailability(product, availableByProduct, availableKits, saleUnitFactors);
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
     * Disponible (cantidad - reservado) en unidad base por producto en la sucursal que atiende el
     * e-commerce. La ubicacion se resuelve por la politica operativa del inventario; el storefront
     * nunca decide ni suma estantes por su cuenta.
     */
    private Map<UUID, BigDecimal> availableByProduct(UUID tenantId) {
        return ecommerceConfigRepository.findByTenantId(tenantId)
                .map(EcommerceConfig::getDefaultBranchId)
                .map(branchId -> inventoryOperationalLocationService.availableByProduct(tenantId, branchId))
                .orElse(Map.of());
    }

    /**
     * El contrato publico expresa cantidades en la unica unidad de venta del producto. Solo se
     * publican empaques completos, igual que el checkout que convierte venta -> unidad base antes
     * de reservar inventario.
     */
    private static StockAvailability stockAvailability(
            Product product,
            Map<UUID, BigDecimal> availableByProduct,
            Map<UUID, BigDecimal> availableKits,
            Map<UUID, BigDecimal> saleUnitFactors) {
        if (product.getProductType() == ProductType.kit) {
            BigDecimal available = availableKits.getOrDefault(product.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            return new StockAvailability(available.signum() > 0, available);
        }
        if (!Boolean.TRUE.equals(product.getTrackingStock())) {
            return new StockAvailability(true, null);
        }
        BigDecimal available = availableByProduct.getOrDefault(product.getId(), BigDecimal.ZERO);
        BigDecimal factor = saleUnitFactors.get(product.getId());
        if (factor == null || factor.signum() <= 0) {
            return new StockAvailability(false, BigDecimal.ZERO);
        }
        available = available.max(BigDecimal.ZERO).divide(factor, 0, RoundingMode.DOWN);
        return new StockAvailability(available.signum() > 0, available.max(BigDecimal.ZERO));
    }

    /** La capacidad de un kit es el menor número entero de kits completos que permiten sus componentes. */
    private Map<UUID, BigDecimal> availableKits(
            UUID tenantId, List<Product> products, Map<UUID, BigDecimal> availableByProduct) {
        List<UUID> kitIds = products.stream()
                .filter(product -> product.getProductType() == ProductType.kit)
                .map(Product::getId)
                .toList();
        if (kitIds.isEmpty()) return Map.of();
        Map<UUID, List<com.omniretail.backend.catalog.entity.ProductKitComponent>> componentsByKit =
                productKitComponentRepository.findByTenantIdAndKitProductIdIn(tenantId, kitIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                com.omniretail.backend.catalog.entity.ProductKitComponent::getKitProductId));
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (UUID kitId : kitIds) {
            List<com.omniretail.backend.catalog.entity.ProductKitComponent> components = componentsByKit.get(kitId);
            if (components == null || components.isEmpty()) {
                result.put(kitId, BigDecimal.ZERO);
                continue;
            }
            BigDecimal capacity = components.stream()
                    .map(component -> availableByProduct.getOrDefault(
                                    component.getComponentProductId(), BigDecimal.ZERO)
                            .max(BigDecimal.ZERO)
                            .divide(component.getQuantityPerKit(), 0, RoundingMode.DOWN))
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            result.put(kitId, capacity.max(BigDecimal.ZERO));
        }
        return result;
    }

    private Map<UUID, BigDecimal> saleUnitFactors(UUID tenantId, List<Product> products) {
        Map<ProductUnitPair, UnitConversion> productConversions = new HashMap<>();
        Map<UnitPair, UnitConversion> tenantConversions = new HashMap<>();
        for (UnitConversion conversion : unitConversionRepository.findByTenantId(tenantId)) {
            UnitPair pair = new UnitPair(conversion.getFromUnitId(), conversion.getToUnitId());
            if (conversion.getProductId() == null) {
                tenantConversions.put(pair, conversion);
            } else {
                productConversions.put(new ProductUnitPair(
                        conversion.getProductId(), conversion.getFromUnitId(), conversion.getToUnitId()), conversion);
            }
        }

        Map<UUID, BigDecimal> factors = new HashMap<>();
        for (Product product : products) {
            UUID saleUnitId = product.getSaleUnitId() != null ? product.getSaleUnitId() : product.getBaseUnitId();
            if (saleUnitId.equals(product.getBaseUnitId())) {
                factors.put(product.getId(), BigDecimal.ONE);
                continue;
            }
            UnitConversion conversion = productConversions.get(new ProductUnitPair(
                    product.getId(), saleUnitId, product.getBaseUnitId()));
            if (conversion == null) {
                conversion = tenantConversions.get(new UnitPair(saleUnitId, product.getBaseUnitId()));
            }
            if (conversion != null && conversion.getFactor() != null && conversion.getFactor().signum() > 0) {
                factors.put(product.getId(), conversion.getFactor());
            }
        }
        return factors;
    }

    private record StockAvailability(boolean inStock, BigDecimal availableQuantity) {}

    private record UnitPair(UUID fromUnitId, UUID toUnitId) {}

    private record ProductUnitPair(UUID productId, UUID fromUnitId, UUID toUnitId) {}

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
