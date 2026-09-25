package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public PageResponse<ProductDto> list(Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        return PageResponse.from(productRepository.findByTenantId(tenantId, pageable), ProductService::toDto);
    }

    @Transactional
    public ProductDto create(ProductCreateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        validateSkuIsAvailable(tenantId, request.sku());
        validateCatalogReferences(tenantId, request);

        Product product = Product.builder()
                .sku(request.sku())
                .barcode(request.barcode())
                .name(request.name())
                .description(request.description())
                .brand(request.brand())
                .productType(request.productType())
                .categoryId(request.categoryId())
                .baseUnitId(request.baseUnitId())
                .inventoryUnitId(request.inventoryUnitId())
                .saleUnitId(request.saleUnitId())
                .salePrice(request.salePrice())
                .status(request.status())
                .trackingStock(request.tracking().stock())
                .trackingLot(request.tracking().lot())
                .trackingExpiration(request.tracking().expiration())
                .trackingSerial(request.tracking().serial())
                .channelEcommerce(request.channels().ecommerce())
                .channelPos(request.channels().pos())
                .channelMobileApp(request.channels().mobileApp())
                .build();
        product.setTenantId(tenantId);
        return toDto(productRepository.saveAndFlush(product));
    }

    private void validateSkuIsAvailable(UUID tenantId, String sku) {
        if (productRepository.existsByTenantIdAndSku(tenantId, sku)) {
            throw BusinessException.conflict("PRODUCT_SKU_CONFLICT", "Ya existe un producto con ese SKU.");
        }
    }

    private void validateCatalogReferences(UUID tenantId, ProductCreateRequest request) {
        if (!categoryRepository.existsByIdAndTenantId(request.categoryId(), tenantId)) {
            throw BusinessException.notFound("La categoria no existe.");
        }
        requireOwnedUnit(request.baseUnitId(), tenantId);
        if (request.inventoryUnitId() != null) {
            requireOwnedUnit(request.inventoryUnitId(), tenantId);
        }
        if (request.saleUnitId() != null) {
            requireOwnedUnit(request.saleUnitId(), tenantId);
        }
    }

    private void requireOwnedUnit(UUID unitId, UUID tenantId) {
        if (!unitRepository.existsByIdAndTenantId(unitId, tenantId)) {
            throw BusinessException.notFound("La unidad no existe.");
        }
    }

    private static ProductDto toDto(Product product) {
        return new ProductDto(
                product.getId(),
                product.getTenantId(),
                product.getSku(),
                product.getBarcode(),
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                product.getProductType(),
                product.getCategoryId(),
                product.getBaseUnitId(),
                product.getInventoryUnitId(),
                product.getSaleUnitId(),
                product.getSalePrice(),
                product.getStatus(),
                new ProductTrackingDto(
                        product.getTrackingStock(),
                        product.getTrackingLot(),
                        product.getTrackingExpiration(),
                        product.getTrackingSerial()),
                new ProductChannelsDto(
                        product.getChannelEcommerce(), product.getChannelPos(), product.getChannelMobileApp()),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
