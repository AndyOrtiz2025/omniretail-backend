package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.CrossBranchStockDto;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.dto.InventoryKitAvailabilityComponentDto;
import com.omniretail.backend.inventory.dto.InventoryKitAvailabilityResponse;
import com.omniretail.backend.inventory.dto.InventoryProductMode;
import com.omniretail.backend.inventory.dto.InventoryStockDisplayStatus;
import com.omniretail.backend.inventory.dto.InventoryStockItemDto;
import com.omniretail.backend.inventory.dto.InventoryStockPageResponse;
import com.omniretail.backend.inventory.dto.InventoryStockSummaryDto;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository.CrossBranchStockProjection;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository.InventoryStockProjection;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository.InventoryStockSummaryProjection;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository.KitComponentAvailabilityProjection;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryStockQueryService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "productName", "sku", "categoryName", "availableQuantity", "status");

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final ProductInventorySettingsRepository settingsRepository;
    private final TenantBusinessDateService businessDateService;

    private static final Set<ProductType> DEFAULT_PRODUCT_TYPES = Set.of(ProductType.physical);

    /** Compatibilidad: sin {@code productTypes} el listado es exactamente el de productos físicos. */
    public InventoryStockPageResponse list(
            UUID branchId,
            String search,
            UUID categoryId,
            InventoryAlertStatus status,
            Pageable requestedPageable) {
        return list(branchId, search, categoryId, status, null, requestedPageable);
    }

    /**
     * Listado mezclado de stock. {@code productTypes} (default physical) elige qué filas se devuelven: physical
     * (stock real), service (informativa) y kit (disponibilidad derivada). Los KPIs siguen siendo solo físicos.
     */
    public InventoryStockPageResponse list(
            UUID branchId,
            String search,
            UUID categoryId,
            InventoryAlertStatus status,
            Collection<ProductType> productTypes,
            Pageable requestedPageable) {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.inventory);
        requireBranchAndAccess(actor, branchId);

        SortSelection sort = sort(requestedPageable);
        Pageable pageable = PageRequest.of(
                Math.max(requestedPageable.getPageNumber(), 0),
                Math.min(Math.max(requestedPageable.getPageSize(), 1), 100));
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        String statusValue = status == null ? null : status.name();
        Set<ProductType> types = productTypes == null || productTypes.isEmpty()
                ? DEFAULT_PRODUCT_TYPES
                : EnumSet.copyOf(productTypes);
        LocalDate businessDate = businessDateService.currentDate(actor.tenantId());
        Page<InventoryStockProjection> page = settingsRepository.findStock(
                actor.tenantId(), branchId, normalizedSearch, categoryId, statusValue,
                types.contains(ProductType.physical),
                types.contains(ProductType.service),
                types.contains(ProductType.kit),
                businessDate, sort.field(), sort.direction(), pageable);
        InventoryStockSummaryProjection summary = settingsRepository.summarizeStock(
                actor.tenantId(), branchId, normalizedSearch, categoryId, statusValue, businessDate);
        return new InventoryStockPageResponse(
                page.getContent().stream().map(InventoryStockQueryService::item).toList(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                new InventoryStockSummaryDto(
                        summary.getActiveProducts(),
                        summary.getLowStock(),
                        summary.getExpiringSoonProducts(),
                        summary.getOutOfStock()));
    }

    /**
     * Explica la disponibilidad derivada de un kit en una sucursal: capacidad por componente y cuáles limitan.
     * Solo lectura; mismas validaciones de capability, tenant y sucursal que el listado de stock.
     */
    public InventoryKitAvailabilityResponse kitAvailability(UUID kitProductId, UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        requireBranchAndAccess(actor, branchId);
        Product product = productRepository.findByTenantIdAndId(tenantId, kitProductId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        if (product.getProductType() != ProductType.kit) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_KIT_AVAILABILITY_PRODUCT_UNSUPPORTED",
                    "La disponibilidad derivada solo aplica a productos de tipo kit.");
        }

        List<KitComponentAvailabilityProjection> rows =
                settingsRepository.findKitComponentAvailability(tenantId, branchId, kitProductId);
        long availableKits = rows.stream()
                .mapToLong(row -> row.getKitCapacity().longValue())
                .min()
                .orElse(0L);
        List<InventoryKitAvailabilityComponentDto> components = rows.stream()
                .map(row -> new InventoryKitAvailabilityComponentDto(
                        row.getComponentProductId(),
                        row.getSku(),
                        row.getProductName(),
                        row.getQuantityPerKit(),
                        row.getAvailableQuantity(),
                        row.getKitCapacity().longValue(),
                        row.getKitCapacity().longValue() == availableKits))
                .toList();
        return new InventoryKitAvailabilityResponse(kitProductId, branchId, availableKits, components);
    }

    public List<CrossBranchStockDto> listBranches(UUID productId, UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        BranchAccess access = requireBranchAndAccess(actor, branchId);
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        // Servicio y kit no tienen stock propio por sucursal: se rechazan en vez de devolver ceros engañosos.
        // La disponibilidad derivada del kit solo se expone por sucursal en GET /inventory/stock.
        if (product.getProductType() != ProductType.physical) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_STOCK_BRANCHES_PRODUCT_UNSUPPORTED",
                    "El stock por sucursal solo aplica a productos fisicos.");
        }

        List<CrossBranchStockProjection> rows = access.allBranches()
                ? inventoryBalanceRepository.findCrossBranchStock(tenantId, productId, branchId)
                : inventoryBalanceRepository.findCrossBranchStockIn(
                        tenantId, productId, branchId, access.branchIds());
        return rows.stream()
                .map(row -> new CrossBranchStockDto(
                        row.getBranchId(), row.getBranchName(), row.getAvailableQuantity()))
                .toList();
    }

    private BranchAccess requireBranchAndAccess(AuthenticatedUser actor, UUID branchId) {
        if (branchId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BRANCH_REQUIRED", "La sucursal es requerida.");
        }
        branchRepository.findByTenantIdAndId(actor.tenantId(), branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden("BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        return access;
    }

    private static SortSelection sort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) return new SortSelection("productName", "asc");
        if (pageable.getSort().stream().count() != 1) {
            throw invalidSort();
        }
        var order = pageable.getSort().iterator().next();
        if (!SORT_FIELDS.contains(order.getProperty())) throw invalidSort();
        return new SortSelection(order.getProperty(), order.getDirection().name().toLowerCase(Locale.ROOT));
    }

    private static BusinessException invalidSort() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVENTORY_STOCK_SORT_INVALID",
                "El ordenamiento de stock admite productName, sku, categoryName, availableQuantity o status.");
    }

    private static InventoryStockItemDto item(InventoryStockProjection row) {
        return new InventoryStockItemDto(
                row.getProductId(), row.getBranchId(), row.getSku(), row.getProductName(),
                row.getCategoryId(), row.getCategoryName(), row.getBaseUnitId(), row.getQuantity(),
                row.getReservedQuantity(), row.getAvailableQuantity(), row.getMinStock(),
                row.getReorderPoint(), row.getDefaultLocationId(), row.getDefaultLocationName(),
                row.getNextExpirationDate(),
                row.getStockStatus() == null ? null : InventoryAlertStatus.valueOf(row.getStockStatus()),
                row.getSuggestedReorder(),
                ProductType.valueOf(row.getProductType()),
                InventoryProductMode.valueOf(row.getInventoryMode()),
                InventoryStockDisplayStatus.valueOf(row.getDisplayStatus()),
                row.getInventoryUnitId(),
                row.getSaleUnitId(),
                row.getInventoryToBaseFactor(),
                row.getSaleToBaseFactor());
    }

    private record SortSelection(String field, String direction) {}
}
