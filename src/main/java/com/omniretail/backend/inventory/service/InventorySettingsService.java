package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.inventory.dto.ProductInventorySettingsResponse;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class InventorySettingsService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final UnitRepository unitRepository;
    private final LocationRepository locationRepository;
    private final ProductInventorySettingsRepository settingsRepository;

    @Transactional(readOnly = true)
    public PageResponse<ProductInventorySettingsResponse> list(
            UUID branchId, UUID productId, Pageable requestedPageable) {
        AuthenticatedUser actor = requireInventoryActor();
        requireBranchAndAccess(actor, branchId);
        if (productId != null) {
            requireProduct(actor.tenantId(), productId);
        }
        return PageResponse.from(
                settingsRepository.findPage(
                        actor.tenantId(), branchId, productId, safePageable(requestedPageable)),
                ProductInventorySettingsResponse::from);
    }

    @Transactional(readOnly = true)
    public Optional<ProductInventorySettingsResponse> get(UUID branchId, UUID productId) {
        AuthenticatedUser actor = requireInventoryActor();
        requireBranchAndAccess(actor, branchId);
        requireProduct(actor.tenantId(), productId);
        return settingsRepository
                .findByTenantIdAndBranchIdAndProductId(actor.tenantId(), branchId, productId)
                .map(ProductInventorySettingsResponse::from);
    }

    public ProductInventorySettingsResponse upsert(
            UUID branchId, UUID productId, UpdateInventorySettingsRequest request) {
        AuthenticatedUser actor = requireInventoryActor();
        requireBranchAndAccess(actor, branchId);
        if (request == null) {
            throw invalidQuantity();
        }
        Product product = requireProduct(actor.tenantId(), productId);
        requireEligible(product);
        Unit baseUnit = unitRepository
                .findByTenantIdAndId(actor.tenantId(), product.getBaseUnitId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "Unidad base no encontrada."));
        BigDecimal minStock = validateQuantity(request.minStock(), baseUnit, false);
        BigDecimal reorderPoint = validateQuantity(request.reorderPoint(), baseUnit, true);
        UUID defaultLocationId = validateLocation(
                actor.tenantId(), branchId, request.defaultLocationId());

        settingsRepository.upsert(
                actor.tenantId(),
                branchId,
                productId,
                minStock,
                reorderPoint,
                defaultLocationId);
        return settingsRepository
                .findByTenantIdAndBranchIdAndProductId(actor.tenantId(), branchId, productId)
                .map(ProductInventorySettingsResponse::from)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo recuperar la configuración de inventario actualizada."));
    }

    private AuthenticatedUser requireInventoryActor() {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.inventory);
        return actor;
    }

    private void requireBranchAndAccess(AuthenticatedUser actor, UUID branchId) {
        if (branchId == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "BRANCH_REQUIRED", "La sucursal es requerida.");
        }
        branchRepository
                .findByTenantIdAndId(actor.tenantId(), branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository
                .findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private static void requireEligible(Product product) {
        if (!Boolean.TRUE.equals(product.getTrackingStock())
                || product.getProductType() != ProductType.physical) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_SETTINGS_PRODUCT_NOT_ELIGIBLE",
                    "Solo los productos físicos con control de inventario admiten esta configuración.");
        }
    }

    private static BigDecimal validateQuantity(
            BigDecimal value, Unit baseUnit, boolean nullable) {
        if (value == null) {
            if (nullable) {
                return null;
            }
            throw invalidQuantity();
        }
        if (value.signum() < 0 || !fitsDecimal(value, 12, 3)) {
            throw invalidQuantity();
        }
        if (!baseUnit.getAllowsDecimals() && !isInteger(value)) {
            throw invalidQuantity();
        }
        try {
            return value.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalidQuantity();
        }
    }

    private UUID validateLocation(UUID tenantId, UUID branchId, UUID locationId) {
        if (locationId == null) {
            return null;
        }
        Location location = locationRepository
                .findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "INVENTORY_SETTINGS_LOCATION_NOT_FOUND",
                        "Ubicación no encontrada."));
        if (!location.getBranchId().equals(branchId)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_SETTINGS_LOCATION_BRANCH_MISMATCH",
                    "La ubicación no pertenece a la sucursal seleccionada.");
        }
        if (location.getStatus() != LocationStatus.active) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_SETTINGS_LOCATION_NOT_ACTIVE",
                    "La ubicación predeterminada debe estar activa.");
        }
        return location.getId();
    }

    private static boolean fitsDecimal(BigDecimal value, int precision, int scale) {
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionDigits <= scale && integerDigits <= precision - scale;
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static Pageable safePageable(Pageable requested) {
        int size = Math.min(Math.max(requested.getPageSize(), 1), 100);
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                size,
                Sort.by(Sort.Order.asc("productId"), Sort.Order.asc("id")));
    }

    private static BusinessException invalidQuantity() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVENTORY_SETTINGS_INVALID_QUANTITY",
                "Las cantidades deben ser valores válidos expresados en la unidad base.");
    }
}
