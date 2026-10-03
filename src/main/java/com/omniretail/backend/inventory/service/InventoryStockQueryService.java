package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.dto.InventoryStockItemDto;
import com.omniretail.backend.inventory.dto.InventoryStockPageResponse;
import com.omniretail.backend.inventory.dto.InventoryStockSummaryDto;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository.InventoryStockProjection;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository.InventoryStockSummaryProjection;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.LocalDate;
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
    private final ProductInventorySettingsRepository settingsRepository;
    private final TenantBusinessDateService businessDateService;

    public InventoryStockPageResponse list(
            UUID branchId,
            String search,
            UUID categoryId,
            InventoryAlertStatus status,
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
        LocalDate businessDate = businessDateService.currentDate(actor.tenantId());
        Page<InventoryStockProjection> page = settingsRepository.findStock(
                actor.tenantId(), branchId, normalizedSearch, categoryId, statusValue,
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

    private void requireBranchAndAccess(AuthenticatedUser actor, UUID branchId) {
        if (branchId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BRANCH_REQUIRED", "La sucursal es requerida.");
        }
        branchRepository.findByTenantIdAndId(actor.tenantId(), branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (!branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden("BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
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
                row.getNextExpirationDate(), InventoryAlertStatus.valueOf(row.getStockStatus()),
                row.getSuggestedReorder());
    }

    private record SortSelection(String field, String direction) {}
}
