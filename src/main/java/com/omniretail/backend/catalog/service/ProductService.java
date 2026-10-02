package com.omniretail.backend.catalog.service;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductChannel;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductListDto;
import com.omniretail.backend.catalog.dto.ProductPromotionFilter;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.dto.ProductUpdateRequest;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductPriceHistory;
import com.omniretail.backend.catalog.entity.ProductMediaType;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductMediaRepository;
import com.omniretail.backend.catalog.repository.ProductSpecifications;
import com.omniretail.backend.catalog.repository.ProductPriceHistoryRepository;
import com.omniretail.backend.catalog.repository.ProductKitComponentRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderItemRepository;
import com.omniretail.backend.purchasing.repository.SupplierProductRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final String SKU_CONSTRAINT = "uk_products_tenant_sku";
    private static final String BARCODE_CONSTRAINT = "uk_products_tenant_barcode";

    private final ProductRepository productRepository;
    private final ProductMediaRepository productMediaRepository;
    private final ProductPriceHistoryRepository productPriceHistoryRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final UnitConversionRepository unitConversionRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SaleItemRepository saleItemRepository;
    private final ProductKitComponentRepository productKitComponentRepository;
    private final ProductKitService productKitService;
    private final BusinessConfigService businessConfigService;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public PageResponse<ProductListDto> list(Pageable pageable) {
        return list(null, null, null, null, List.of(), ProductPromotionFilter.all, pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductListDto> list(
            String search,
            ProductStatus status,
            ProductType productType,
            UUID categoryId,
            List<ProductChannel> channels,
            ProductPromotionFilter promotion,
            Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        validateProductSort(pageable);
        Instant now = Instant.now();
        Page<Product> products = productRepository.findAll(
                ProductSpecifications.filtered(
                        tenantId,
                        search,
                        status,
                        productType,
                        categoryId,
                        channels,
                        promotion == null ? ProductPromotionFilter.all : promotion,
                        now),
                pageable);
        List<UUID> productIds = products.getContent().stream().map(Product::getId).toList();
        Map<UUID, String> primaryImages = new HashMap<>();
        if (!productIds.isEmpty()) {
            productMediaRepository
                    .findByTenantIdAndProductIdInAndPrimaryTrueAndTypeOrderByProductIdAscSortOrderAscIdAsc(
                            tenantId, productIds, ProductMediaType.image)
                    .forEach(media -> primaryImages.putIfAbsent(media.getProductId(), media.getUrl()));
        }
        return PageResponse.from(products, product -> toListDto(product, primaryImages.get(product.getId())));
    }

    private static final Set<String> PRODUCT_SORT_FIELDS = Set.of("name", "sku", "createdAt");

    private static void validateProductSort(Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!PRODUCT_SORT_FIELDS.contains(order.getProperty())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PRODUCT_SORT_INVALID",
                        "El ordenamiento de productos solo admite name, sku o createdAt.");
            }
        });
    }

    @Transactional(readOnly = true)
    public ProductDto get(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        return toDto(requireProduct(tenantId, id));
    }

    @Transactional
    public ProductDto create(ProductCreateRequest request) {
        var actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        String sku = normalizeSku(request.sku());
        String barcode = normalizeBarcode(request.barcode());
        validateSkuIsAvailable(tenantId, sku, null);
        validateBarcodeIsAvailable(tenantId, barcode, null);
        validateCatalogReferencesForCreate(tenantId, request);
        BusinessConfigResponse config = businessConfigService.getConfig();
        requireSupportedType(request.productType(), config, tenantId);
        if (request.productType() == ProductType.kit && request.status() == ProductStatus.published) {
            throw BusinessException.conflict("KIT_COMPONENTS_REQUIRED",
                    "Crea el kit archivado, configura sus componentes y luego restauralo.");
        }
        requireUnitsAndPackagingForCreate(request, config);

        TrackingValues tracking = effectiveTracking(request.productType(), request.tracking());
        validateTrackingCoherence(tracking);
        validateBusinessCapabilitiesForCreate(request.productType(), tracking, config);
        requireTraceabilityEntitlements(tenantId, TrackingValues.NONE, tracking);

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
                .trackingStock(tracking.stock())
                .trackingLot(tracking.lot())
                .trackingExpiration(tracking.expiration())
                .trackingSerial(tracking.serial())
                .channelEcommerce(request.channels().ecommerce())
                .channelPos(request.channels().pos())
                .channelMobileApp(request.channels().mobileApp())
                .build();
        product.setTenantId(tenantId);
        Product saved = saveWithUniqueTranslation(product);
        productPriceHistoryRepository.saveAndFlush(ProductPriceHistory.builder()
                .tenantId(tenantId)
                .productId(saved.getId())
                .oldPrice(null)
                .newPrice(saved.getSalePrice())
                .changedByUserId(actor.userId())
                .reason("Creación de producto")
                .build());
        return toDto(saved);
    }

    @Transactional
    public ProductDto update(UUID id, ProductUpdateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Product product = requireProductForUpdate(tenantId, id);
        if (product.getStatus() == ProductStatus.archived) {
            throw BusinessException.conflict("PRODUCT_ARCHIVED", "Un producto archivado no puede editarse.");
        }

        String sku = normalizeSku(request.sku());
        String barcode = normalizeBarcode(request.barcode());
        validateSkuIsAvailable(tenantId, sku, product.getId());
        validateBarcodeIsAvailable(tenantId, barcode, product.getId());
        BusinessConfigResponse config = businessConfigService.getConfig();

        boolean typeChanged = request.productType() != product.getProductType();
        if (typeChanged && (request.productType() == ProductType.kit
                || product.getProductType() == ProductType.kit)) {
            throw kitNotSupported();
        }
        if (typeChanged) {
            if (hasStructuralHistory(tenantId, product.getId())) {
                throw BusinessException.conflict(
                        "PRODUCT_TYPE_CHANGE_NOT_ALLOWED",
                        "El tipo del producto no puede cambiar porque posee historial estructural.");
            }
            requireSupportedType(request.productType(), config, tenantId);
        }

        validateChangedCategory(tenantId, product, request.categoryId());
        boolean baseUnitChanged = !request.baseUnitId().equals(product.getBaseUnitId());
        if (baseUnitChanged) {
            requireOwnedActiveUnit(request.baseUnitId(), tenantId);
            if (hasStructuralHistory(tenantId, product.getId())) {
                throw BusinessException.conflict(
                        "PRODUCT_BASE_UNIT_CHANGE_NOT_ALLOWED",
                        "La unidad base no puede cambiar porque el producto posee historial estructural.");
            }
        }
        validateChangedOptionalUnit(tenantId, product.getInventoryUnitId(), request.inventoryUnitId());
        validateChangedOptionalUnit(tenantId, product.getSaleUnitId(), request.saleUnitId());
        validateUnitsAndPackagingForUpdate(product, request, config);

        TrackingValues currentTracking = TrackingValues.from(product);
        validateKitTrackingForUpdate(request.productType(), request.tracking());
        TrackingValues requestedTracking = effectiveTracking(request.productType(), request.tracking());
        if (productKitComponentRepository.existsInPublishedKit(tenantId, product.getId())
                && (request.productType() != ProductType.physical || !requestedTracking.stock())) {
            throw BusinessException.conflict("KIT_COMPONENT_IN_USE",
                    "El producto debe seguir siendo fisico y controlar inventario mientras sea componente de un kit publicado.");
        }
        validateTrackingCoherence(requestedTracking);
        validateBusinessCapabilitiesForUpdate(
                product.getProductType(), request.productType(), currentTracking, requestedTracking, config);
        requireTraceabilityEntitlements(tenantId, currentTracking, requestedTracking);
        if (!currentTracking.equals(requestedTracking)
                && hasTrackingHistory(
                        tenantId,
                        product.getId(),
                        currentTracking.stock() != requestedTracking.stock())) {
            throw BusinessException.conflict(
                    "PRODUCT_TRACKING_CHANGE_NOT_ALLOWED",
                    "La configuración de seguimiento no puede cambiar porque el producto posee historial operativo.");
        }

        product.setSku(sku);
        product.setBarcode(barcode);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setBrand(request.brand());
        product.setProductType(request.productType());
        product.setCategoryId(request.categoryId());
        product.setBaseUnitId(request.baseUnitId());
        product.setInventoryUnitId(request.inventoryUnitId());
        product.setSaleUnitId(request.saleUnitId());
        product.setTrackingStock(requestedTracking.stock());
        product.setTrackingLot(requestedTracking.lot());
        product.setTrackingExpiration(requestedTracking.expiration());
        product.setTrackingSerial(requestedTracking.serial());
        product.setChannelEcommerce(request.channels().ecommerce());
        product.setChannelPos(request.channels().pos());
        product.setChannelMobileApp(request.channels().mobileApp());
        // salePrice y status pertenecen a flujos dedicados y se preservan.
        return toDto(saveWithUniqueTranslation(product));
    }

    @Transactional
    public void archive(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Product product = requireProductForUpdate(tenantId, id);
        if (product.getStatus() == ProductStatus.archived) {
            return;
        }
        if (inventoryBalanceRepository.existsPositiveStockByTenantIdAndProductId(tenantId, product.getId())) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVE_HAS_STOCK",
                    "El producto no puede archivarse mientras tenga existencias o reservas.");
        }
        if (purchaseOrderItemRepository.existsOpenOrderForProduct(tenantId, product.getId())) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVE_HAS_OPEN_PO",
                    "El producto no puede archivarse porque aparece en una orden de compra abierta.");
        }
        if (productKitComponentRepository.existsInPublishedKit(tenantId, product.getId())) {
            throw BusinessException.conflict("KIT_COMPONENT_IN_USE",
                    "El producto es componente de un kit publicado y no puede archivarse.");
        }
        product.setStatus(ProductStatus.archived);
        productRepository.saveAndFlush(product);
    }

    @Transactional
    public ProductDto restore(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Product product = requireProductForUpdate(tenantId, id);
        if (product.getStatus() == ProductStatus.published) return toDto(product);
        requireActiveCategory(product.getCategoryId(), tenantId);
        requireOwnedActiveUnit(product.getBaseUnitId(), tenantId);
        if (product.getInventoryUnitId() != null) requireOwnedActiveUnit(product.getInventoryUnitId(), tenantId);
        if (product.getSaleUnitId() != null) requireOwnedActiveUnit(product.getSaleUnitId(), tenantId);
        BusinessConfigResponse config = businessConfigService.getConfig();
        requireSupportedType(product.getProductType(), config, tenantId);
        TrackingValues tracking = effectiveTracking(product.getProductType(), new ProductTrackingDto(
                product.getTrackingStock(), product.getTrackingLot(), product.getTrackingExpiration(), product.getTrackingSerial()));
        validateTrackingCoherence(tracking);
        validateBusinessCapabilitiesForCreate(product.getProductType(), tracking, config);
        requireTraceabilityEntitlements(tenantId, TrackingValues.NONE, tracking);
        if (product.getProductType() == ProductType.kit) productKitService.validatePublishable(tenantId, product);
        product.setStatus(ProductStatus.published);
        return toDto(productRepository.saveAndFlush(product));
    }

    private Product saveWithUniqueTranslation(Product product) {
        try {
            return productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            String detail = exceptionDetail(exception);
            if (detail.contains(SKU_CONSTRAINT)) {
                throw skuConflict();
            }
            if (detail.contains(BARCODE_CONSTRAINT)) {
                throw barcodeConflict();
            }
            throw exception;
        }
    }

    private Product requireProduct(UUID tenantId, UUID id) {
        return productRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private Product requireProductForUpdate(UUID tenantId, UUID id) {
        return productRepository.findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private void validateSkuIsAvailable(UUID tenantId, String sku, UUID currentId) {
        boolean exists = currentId == null
                ? productRepository.existsByTenantIdAndSku(tenantId, sku)
                : productRepository.existsByTenantIdAndSkuAndIdNot(tenantId, sku, currentId);
        if (exists) {
            throw skuConflict();
        }
    }

    private void validateBarcodeIsAvailable(UUID tenantId, String barcode, UUID currentId) {
        if (barcode == null) {
            return;
        }
        boolean exists = currentId == null
                ? productRepository.existsByTenantIdAndBarcode(tenantId, barcode)
                : productRepository.existsByTenantIdAndBarcodeAndIdNot(tenantId, barcode, currentId);
        if (exists) {
            throw barcodeConflict();
        }
    }

    private void validateCatalogReferencesForCreate(UUID tenantId, ProductCreateRequest request) {
        requireActiveCategory(request.categoryId(), tenantId);
        requireOwnedActiveUnit(request.baseUnitId(), tenantId);
        if (request.inventoryUnitId() != null) {
            requireOwnedActiveUnit(request.inventoryUnitId(), tenantId);
        }
        if (request.saleUnitId() != null) {
            requireOwnedActiveUnit(request.saleUnitId(), tenantId);
        }
    }

    private void validateChangedCategory(UUID tenantId, Product product, UUID requestedCategoryId) {
        if (!requestedCategoryId.equals(product.getCategoryId())) {
            requireActiveCategory(requestedCategoryId, tenantId);
        }
    }

    private void requireActiveCategory(UUID categoryId, UUID tenantId) {
        if (!categoryRepository.existsByIdAndTenantIdAndStatus(
                categoryId, tenantId, CategoryStatus.active)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "CATEGORY_NOT_FOUND",
                    "La categoría no existe o se encuentra inactiva.");
        }
    }

    private void validateChangedOptionalUnit(UUID tenantId, UUID current, UUID requested) {
        if (!java.util.Objects.equals(current, requested) && requested != null) {
            requireOwnedActiveUnit(requested, tenantId);
        }
    }

    private void requireOwnedActiveUnit(UUID unitId, UUID tenantId) {
        if (!unitRepository.existsByIdAndTenantIdAndStatus(unitId, tenantId, UnitStatus.active)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "UNIT_NOT_FOUND",
                    "La unidad no existe o se encuentra inactiva.");
        }
    }

    private void requireSupportedType(ProductType type, BusinessConfigResponse config, UUID tenantId) {
        if (type == ProductType.kit) {
            if (!config.supportsKits()) throw capabilityDisabled("El negocio no tiene habilitados los kits.");
            tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.catalogKits);
        }
        if (type == ProductType.service && !config.supportsServices()) {
            throw capabilityDisabled("El negocio no tiene habilitados los productos de servicio.");
        }
    }

    private static TrackingValues effectiveTracking(ProductType type, ProductTrackingDto requested) {
        if (type == ProductType.service || type == ProductType.kit) {
            return TrackingValues.NONE;
        }
        return new TrackingValues(
                Boolean.TRUE.equals(requested.stock()),
                Boolean.TRUE.equals(requested.lot()),
                Boolean.TRUE.equals(requested.expiration()),
                Boolean.TRUE.equals(requested.serial()));
    }

    private static void validateKitTrackingForUpdate(
            ProductType requestedType, ProductTrackingDto requestedTracking) {
        if (requestedType == ProductType.kit
                && (Boolean.TRUE.equals(requestedTracking.stock())
                        || Boolean.TRUE.equals(requestedTracking.lot())
                        || Boolean.TRUE.equals(requestedTracking.expiration())
                        || Boolean.TRUE.equals(requestedTracking.serial()))) {
            throw capabilityDisabled("Los kits no pueden habilitar seguimiento de inventario propio.");
        }
    }

    private static void validateTrackingCoherence(TrackingValues tracking) {
        if (tracking.expiration() && !tracking.lot()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "PRODUCT_TRACKING_INVALID",
                    "El control por vencimiento requiere control por lote.");
        }
    }

    private static void validateBusinessCapabilitiesForCreate(
            ProductType type, TrackingValues tracking, BusinessConfigResponse config) {
        if (type != ProductType.physical) {
            return;
        }
        requireTrackingBusinessCapabilities(tracking, config, TrackingValues.NONE);
    }

    private static void validateBusinessCapabilitiesForUpdate(
            ProductType currentType,
            ProductType requestedType,
            TrackingValues current,
            TrackingValues requested,
            BusinessConfigResponse config) {
        if (requested.stock() && !current.stock() && requestedType != ProductType.physical) {
            throw capabilityDisabled("Solo los productos físicos pueden habilitar control de inventario.");
        }
        if (requestedType == ProductType.service) {
            if (currentType != ProductType.service && !config.supportsServices()) {
                throw capabilityDisabled("El negocio no tiene habilitados los productos de servicio.");
            }
            return;
        }
        if (requestedType == ProductType.physical) {
            requireTrackingBusinessCapabilities(requested, config, current);
        }
    }

    private static void requireTrackingBusinessCapabilities(
            TrackingValues requested, BusinessConfigResponse config, TrackingValues current) {
        if (requested.stock() && !current.stock() && !config.supportsInventory()) {
            throw capabilityDisabled("El negocio no tiene habilitado el control de inventario.");
        }
        if (requested.lot() && !current.lot() && !config.supportsLots()) {
            throw capabilityDisabled("El negocio no tiene habilitado el control por lotes.");
        }
        if (requested.expiration() && !current.expiration() && !config.supportsExpiration()) {
            throw capabilityDisabled("El negocio no tiene habilitado el control por vencimiento.");
        }
        if (requested.serial() && !current.serial() && !config.supportsSerials()) {
            throw capabilityDisabled("El negocio no tiene habilitado el control por series.");
        }
    }

    private static void requireUnitsAndPackagingForCreate(
            ProductCreateRequest request, BusinessConfigResponse config) {
        if (!config.supportsUnitsAndPackaging()
                && (isAlternate(request.baseUnitId(), request.inventoryUnitId())
                        || isAlternate(request.baseUnitId(), request.saleUnitId()))) {
            throw capabilityDisabled("El negocio no tiene habilitadas unidades alternativas.");
        }
    }

    private static void validateUnitsAndPackagingForUpdate(
            Product product, ProductUpdateRequest request, BusinessConfigResponse config) {
        if (config.supportsUnitsAndPackaging()) {
            return;
        }
        boolean baseChanged = !product.getBaseUnitId().equals(request.baseUnitId());
        boolean inventoryChanged = !java.util.Objects.equals(
                product.getInventoryUnitId(), request.inventoryUnitId());
        boolean saleChanged = !java.util.Objects.equals(product.getSaleUnitId(), request.saleUnitId());
        if (((baseChanged || inventoryChanged)
                        && isAlternate(request.baseUnitId(), request.inventoryUnitId()))
                || ((baseChanged || saleChanged)
                        && isAlternate(request.baseUnitId(), request.saleUnitId()))) {
            throw capabilityDisabled("El negocio no tiene habilitadas unidades alternativas.");
        }
    }

    private void requireTraceabilityEntitlements(
            UUID tenantId, TrackingValues current, TrackingValues requested) {
        if (requested.lot() && !current.lot()) {
            tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.traceabilityLots);
        }
        if (requested.expiration() && !current.expiration()) {
            tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.traceabilityExpiration);
        }
        if (requested.serial() && !current.serial()) {
            tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.traceabilitySerials);
        }
    }

    private boolean hasStructuralHistory(UUID tenantId, UUID productId) {
        return inventoryBalanceRepository.existsPositiveStockByTenantIdAndProductId(tenantId, productId)
                || inventoryMovementRepository.existsByTenantIdAndProductId(tenantId, productId)
                || supplierProductRepository.existsByTenantIdAndProductId(tenantId, productId)
                || purchaseOrderItemRepository.existsByTenantIdAndProductId(tenantId, productId)
                || saleItemRepository.existsByTenantIdAndProductId(tenantId, productId)
                || unitConversionRepository.existsByTenantIdAndProductId(tenantId, productId);
    }

    private boolean hasTrackingHistory(UUID tenantId, UUID productId, boolean stockChanged) {
        return inventoryBalanceRepository.existsPositiveStockByTenantIdAndProductId(tenantId, productId)
                || inventoryMovementRepository.existsByTenantIdAndProductId(tenantId, productId)
                || saleItemRepository.existsByTenantIdAndProductId(tenantId, productId)
                || purchaseOrderItemRepository.existsByTenantIdAndProductId(tenantId, productId)
                || (stockChanged
                        && supplierProductRepository.existsByTenantIdAndProductId(tenantId, productId));
    }

    private static boolean isAlternate(UUID baseUnitId, UUID optionalUnitId) {
        return optionalUnitId != null && !optionalUnitId.equals(baseUnitId);
    }

    private static String normalizeBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        return barcode.trim();
    }

    private static String normalizeSku(String sku) {
        return sku.trim().replaceAll("\\s+", "-").toUpperCase(Locale.ROOT);
    }

    private static String exceptionDetail(Throwable exception) {
        StringBuilder detail = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                detail.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
        }
        return detail.toString();
    }

    private static BusinessException skuConflict() {
        return BusinessException.conflict(
                "PRODUCT_SKU_CONFLICT", "Ya existe un producto con ese SKU.");
    }

    private static BusinessException barcodeConflict() {
        return BusinessException.conflict(
                "PRODUCT_BARCODE_CONFLICT", "Ya existe un producto con ese código de barras.");
    }

    private static BusinessException kitNotSupported() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "PRODUCT_KIT_NOT_SUPPORTED",
                "El tipo kit solo puede definirse durante la creación y no puede convertirse después.");
    }

    private static BusinessException capabilityDisabled(String message) {
        return new BusinessException(
                HttpStatus.BAD_REQUEST, "PRODUCT_CAPABILITY_DISABLED", message);
    }

    static ProductDto toDto(Product product) {
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

    static ProductListDto toListDto(Product product, String primaryImageUrl) {
        return new ProductListDto(
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
                primaryImageUrl,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    private record TrackingValues(boolean stock, boolean lot, boolean expiration, boolean serial) {
        private static final TrackingValues NONE = new TrackingValues(false, false, false, false);

        private static TrackingValues from(Product product) {
            return new TrackingValues(
                    Boolean.TRUE.equals(product.getTrackingStock()),
                    Boolean.TRUE.equals(product.getTrackingLot()),
                    Boolean.TRUE.equals(product.getTrackingExpiration()),
                    Boolean.TRUE.equals(product.getTrackingSerial()));
        }
    }
}
