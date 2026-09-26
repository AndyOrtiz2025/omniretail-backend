package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
        String sku = request.sku().trim();
        String barcode = normalizeBarcode(request.barcode());
        validateSkuIsAvailable(tenantId, sku);
        validateBarcodeIsAvailable(tenantId, barcode);
        validateCatalogReferences(tenantId, request);

        Product product = Product.builder()
                .sku(sku)
                .barcode(barcode)
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

    private void validateBarcodeIsAvailable(UUID tenantId, String barcode) {
        if (barcode != null && productRepository.existsByTenantIdAndBarcode(tenantId, barcode)) {
            throw BusinessException.conflict(
                    "PRODUCT_BARCODE_CONFLICT", "Ya existe un producto con ese codigo de barras.");
        }
    }

    private String normalizeBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        return barcode.trim();
    }

    private void validateCatalogReferences(UUID tenantId, ProductCreateRequest request) {
        if (!categoryRepository.existsByIdAndTenantIdAndStatus(
                request.categoryId(), tenantId, CategoryStatus.active)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "CATEGORY_NOT_FOUND",
                    "La categoria no existe o se encuentra inactiva.");
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
        if (!unitRepository.existsByIdAndTenantIdAndStatus(unitId, tenantId, UnitStatus.active)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "UNIT_NOT_FOUND",
                    "La unidad no existe o se encuentra inactiva.");
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
