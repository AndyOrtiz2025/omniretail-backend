package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.purchasing.dto.CreateSupplierProductRequest;
import com.omniretail.backend.purchasing.dto.ReplaceSupplierCostTiersRequest;
import com.omniretail.backend.purchasing.dto.SupplierCostTierRequest;
import com.omniretail.backend.purchasing.dto.SupplierCostTierResponse;
import com.omniretail.backend.purchasing.dto.SupplierProductResponse;
import com.omniretail.backend.purchasing.dto.UpdateSupplierProductRequest;
import com.omniretail.backend.purchasing.entity.SupplierCostTier;
import com.omniretail.backend.purchasing.entity.SupplierProduct;
import com.omniretail.backend.purchasing.repository.SupplierCostTierRepository;
import com.omniretail.backend.purchasing.repository.SupplierProductRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SupplierProductService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final List<String> OPERATIONAL_PERMISSIONS = List.of(
            "purchasing.orders.read", "purchasing.orders.create", "purchasing.orders.approve");

    private final SupplierProductRepository supplierProductRepository;
    private final SupplierCostTierRepository costTierRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final UnitRepository unitRepository;
    private final BusinessConfigService businessConfigService;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final PermissionResolver permissionResolver;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public PageResponse<SupplierProductResponse> listAdmin(
            UUID supplierId, UUID productId, Boolean active, Boolean preferred, Pageable pageable) {
        UUID tenantId = requirePurchasingTenant();
        Page<SupplierProduct> page = supplierProductRepository.findAdminPage(
                tenantId, supplierId, productId, active, preferred, pageable);
        return PageResponse.from(page, supplierProduct -> response(supplierProduct, List.of()));
    }

    @Transactional(readOnly = true)
    public SupplierProductResponse getAdmin(UUID id) {
        UUID tenantId = requirePurchasingTenant();
        SupplierProduct supplierProduct = requireSupplierProduct(tenantId, id);
        return response(supplierProduct, findTierResponses(tenantId, id));
    }

    @Transactional(readOnly = true)
    public List<SupplierProductResponse> listOperational(UUID supplierId, UUID productId) {
        AuthenticatedUser user = currentUser.require();
        UUID tenantId = user.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.purchasing);
        requireOperationalPermission(user);
        boolean packagingEnabled = businessConfigService.getConfig().supportsUnitsAndPackaging();

        List<SupplierProduct> products = supplierProductRepository.findOperational(
                tenantId, supplierId, productId, packagingEnabled);
        return responsesWithTiers(tenantId, products);
    }

    public SupplierProductResponse create(CreateSupplierProductRequest request) {
        UUID tenantId = requirePurchasingTenant();
        Supplier supplier = requireActiveSupplier(tenantId, request.supplierId());
        Product product = requirePublishedProduct(tenantId, request.productId());
        Unit unit = requireActiveUnit(tenantId, request.purchaseUnitId());
        validateCommercialValues(
                product,
                unit,
                request.purchaseToBaseFactor(),
                request.lastCost(),
                request.leadTimeDays(),
                request.minimumOrderQuantity());

        if (supplierProductRepository.existsByTenantIdAndSupplierIdAndProductId(
                tenantId, supplier.getId(), product.getId())) {
            throw conflict("SUPPLIER_PRODUCT_CONFLICT", "Ya existe la relación entre el proveedor y el producto.");
        }
        boolean preferred = Boolean.TRUE.equals(request.preferred());
        if (preferred
                && supplierProductRepository.existsByTenantIdAndProductIdAndActiveTrueAndPreferredTrue(
                        tenantId, product.getId())) {
            throw conflict("SUPPLIER_PRODUCT_PREFERRED_CONFLICT", "El producto ya tiene un proveedor preferido.");
        }

        SupplierProduct supplierProduct = SupplierProduct.builder()
                .supplierId(supplier.getId())
                .productId(product.getId())
                .supplierSku(normalize(request.supplierSku()))
                .purchaseUnitId(unit.getId())
                .purchaseToBaseFactor(request.purchaseToBaseFactor())
                .lastCost(request.lastCost())
                .leadTimeDays(request.leadTimeDays())
                .minimumOrderQuantity(request.minimumOrderQuantity())
                .preferred(preferred)
                .active(true)
                .build();
        supplierProduct.setTenantId(tenantId);

        try {
            return response(supplierProductRepository.saveAndFlush(supplierProduct), List.of());
        } catch (DataIntegrityViolationException ex) {
            if (preferred) {
                throw conflict(
                        "SUPPLIER_PRODUCT_PREFERRED_CONFLICT", "El producto ya tiene un proveedor preferido.");
            }
            throw conflict("SUPPLIER_PRODUCT_CONFLICT", "Ya existe la relación entre el proveedor y el producto.");
        }
    }

    public SupplierProductResponse update(UUID id, UpdateSupplierProductRequest request) {
        UUID tenantId = requirePurchasingTenant();
        SupplierProduct supplierProduct = requireSupplierProduct(tenantId, id);
        requireActive(supplierProduct);
        requireActiveSupplier(tenantId, supplierProduct.getSupplierId());
        Product product = requirePublishedProduct(tenantId, supplierProduct.getProductId());
        Unit unit = requireActiveUnit(tenantId, request.purchaseUnitId());
        validateCommercialValues(
                product,
                unit,
                request.purchaseToBaseFactor(),
                request.lastCost(),
                request.leadTimeDays(),
                request.minimumOrderQuantity());

        supplierProduct.setSupplierSku(normalize(request.supplierSku()));
        supplierProduct.setPurchaseUnitId(unit.getId());
        supplierProduct.setPurchaseToBaseFactor(request.purchaseToBaseFactor());
        supplierProduct.setLastCost(request.lastCost());
        supplierProduct.setLeadTimeDays(request.leadTimeDays());
        supplierProduct.setMinimumOrderQuantity(request.minimumOrderQuantity());
        return response(supplierProductRepository.saveAndFlush(supplierProduct), List.of());
    }

    public void archive(UUID id) {
        UUID tenantId = requirePurchasingTenant();
        SupplierProduct supplierProduct = requireSupplierProduct(tenantId, id);
        if (!supplierProduct.getActive()) {
            return;
        }
        supplierProduct.setActive(false);
        supplierProduct.setPreferred(false);
        supplierProductRepository.saveAndFlush(supplierProduct);
    }

    public SupplierProductResponse reactivate(UUID id) {
        UUID tenantId = requirePurchasingTenant();
        SupplierProduct supplierProduct = requireSupplierProduct(tenantId, id);
        Supplier supplier = requireActiveSupplier(tenantId, supplierProduct.getSupplierId());
        Product product = requirePublishedProduct(tenantId, supplierProduct.getProductId());
        Unit unit = requireActiveUnit(tenantId, supplierProduct.getPurchaseUnitId());
        validateCommercialValues(
                product,
                unit,
                supplierProduct.getPurchaseToBaseFactor(),
                supplierProduct.getLastCost(),
                supplierProduct.getLeadTimeDays(),
                supplierProduct.getMinimumOrderQuantity());
        if (supplierProduct.getActive()) {
            return response(supplierProduct, findTierResponses(tenantId, id));
        }
        if (supplierProductRepository.existsByTenantIdAndSupplierIdAndProductIdAndIdNot(
                tenantId, supplier.getId(), product.getId(), id)) {
            throw conflict("SUPPLIER_PRODUCT_CONFLICT", "Ya existe la relación entre el proveedor y el producto.");
        }

        supplierProduct.setActive(true);
        supplierProduct.setPreferred(false);
        try {
            SupplierProduct saved = supplierProductRepository.saveAndFlush(supplierProduct);
            return response(saved, findTierResponses(tenantId, id));
        } catch (DataIntegrityViolationException ex) {
            throw conflict("SUPPLIER_PRODUCT_CONFLICT", "Ya existe la relación entre el proveedor y el producto.");
        }
    }

    public SupplierProductResponse setPreferred(UUID id) {
        UUID tenantId = requirePurchasingTenant();
        SupplierProduct selected = requireSupplierProduct(tenantId, id);
        requireActive(selected);
        requireActiveSupplier(tenantId, selected.getSupplierId());
        Product product = requirePublishedProduct(tenantId, selected.getProductId());
        Unit unit = requireActiveUnit(tenantId, selected.getPurchaseUnitId());
        validateCommercialValues(
                product,
                unit,
                selected.getPurchaseToBaseFactor(),
                selected.getLastCost(),
                selected.getLeadTimeDays(),
                selected.getMinimumOrderQuantity());

        supplierProductRepository.clearOtherPreferred(tenantId, selected.getProductId(), selected.getId());
        selected.setPreferred(true);
        try {
            SupplierProduct saved = supplierProductRepository.saveAndFlush(selected);
            return response(saved, findTierResponses(tenantId, id));
        } catch (DataIntegrityViolationException ex) {
            throw conflict(
                    "SUPPLIER_PRODUCT_PREFERRED_CONFLICT",
                    "No se pudo establecer el proveedor preferido por un cambio concurrente.");
        }
    }

    public List<SupplierCostTierResponse> replaceCostTiers(UUID id, ReplaceSupplierCostTiersRequest request) {
        UUID tenantId = requirePurchasingTenant();
        SupplierProduct supplierProduct = requireSupplierProduct(tenantId, id);
        requireActive(supplierProduct);
        Unit unit = requireActiveUnit(tenantId, supplierProduct.getPurchaseUnitId());
        validateTiers(request.tiers(), unit);

        List<SupplierCostTier> replacements = request.tiers().stream()
                .map(tier -> SupplierCostTier.builder()
                        .tenantId(tenantId)
                        .supplierProductId(supplierProduct.getId())
                        .minQuantity(tier.minQuantity())
                        .unitCost(tier.unitCost())
                        .build())
                .toList();

        costTierRepository.deleteByTenantIdAndSupplierProductId(tenantId, supplierProduct.getId());
        try {
            List<SupplierCostTier> saved = replacements.isEmpty()
                    ? List.of()
                    : costTierRepository.saveAllAndFlush(replacements);
            return saved.stream()
                    .sorted(Comparator.comparing(SupplierCostTier::getMinQuantity))
                    .map(SupplierCostTierResponse::from)
                    .toList();
        } catch (DataIntegrityViolationException ex) {
            throw conflict("SUPPLIER_COST_TIER_CONFLICT", "Existen cantidades mínimas duplicadas.");
        }
    }

    private UUID requirePurchasingTenant() {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.purchasing);
        return tenantId;
    }

    private void requireOperationalPermission(AuthenticatedUser user) {
        if (user.roleId() == null
                || OPERATIONAL_PERMISSIONS.stream()
                        .noneMatch(permission -> permissionResolver.hasPermission(
                                user.tenantId(), user.roleId(), permission))) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED", "No tienes permiso para consultar la configuración de compras.");
        }
    }

    private SupplierProduct requireSupplierProduct(UUID tenantId, UUID id) {
        return supplierProductRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "SUPPLIER_PRODUCT_NOT_FOUND",
                        "Relación de proveedor y producto no encontrada."));
    }

    private Supplier requireActiveSupplier(UUID tenantId, UUID supplierId) {
        Supplier supplier = supplierRepository
                .findByTenantIdAndId(tenantId, supplierId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));
        if (supplier.getStatus() != SupplierStatus.active) {
            throw businessError(
                    "SUPPLIER_PRODUCT_SUPPLIER_INACTIVE", "El proveedor no está activo para compras.");
        }
        return supplier;
    }

    private Product requirePublishedProduct(UUID tenantId, UUID productId) {
        Product product = productRepository
                .findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        if (product.getStatus() != ProductStatus.published) {
            throw businessError(
                    "SUPPLIER_PRODUCT_PRODUCT_NOT_AVAILABLE", "El producto no está disponible para compras.");
        }
        if (product.getProductType() == ProductType.kit) {
            throw businessError("SUPPLIER_PRODUCT_KIT_NOT_ALLOWED", "Los kits no se compran directamente a proveedores.");
        }
        return product;
    }

    private Unit requireActiveUnit(UUID tenantId, UUID unitId) {
        Unit unit = unitRepository
                .findByTenantIdAndId(tenantId, unitId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "Unidad no encontrada."));
        if (unit.getStatus() != UnitStatus.active) {
            throw businessError(
                    "SUPPLIER_PRODUCT_UNIT_NOT_AVAILABLE", "La unidad de compra no está activa.");
        }
        return unit;
    }

    private void requireActive(SupplierProduct supplierProduct) {
        if (!supplierProduct.getActive()) {
            throw businessError(
                    "SUPPLIER_PRODUCT_INACTIVE", "La relación debe reactivarse antes de modificarla.");
        }
    }

    private void validateCommercialValues(
            Product product,
            Unit unit,
            BigDecimal factor,
            BigDecimal lastCost,
            Integer leadTimeDays,
            BigDecimal minimumOrderQuantity) {
        validateDecimal(
                factor, 18, 6, false, "SUPPLIER_PRODUCT_INVALID_FACTOR", "El factor de compra no es válido.");
        boolean baseUnit = product.getBaseUnitId().equals(unit.getId());
        if (baseUnit && factor.compareTo(ONE) != 0) {
            throw businessError(
                    "SUPPLIER_PRODUCT_INVALID_FACTOR", "El factor debe ser 1 cuando se usa la unidad base.");
        }

        BusinessConfigResponse config = businessConfigService.getConfig();
        if (!config.supportsUnitsAndPackaging() && (!baseUnit || factor.compareTo(ONE) != 0)) {
            throw businessError(
                    "BUSINESS_CAPABILITY_DISABLED",
                    "Las unidades alternativas requieren habilitar unidades y empaques en el negocio.");
        }

        validateDecimal(
                lastCost, 12, 2, true, "SUPPLIER_PRODUCT_INVALID_COST", "El último costo no es válido.");
        if (leadTimeDays == null || leadTimeDays < 0) {
            throw businessError(
                    "SUPPLIER_PRODUCT_INVALID_LEAD_TIME", "El tiempo de entrega no puede ser negativo.");
        }
        validateQuantity(
                minimumOrderQuantity,
                unit,
                "SUPPLIER_PRODUCT_INVALID_MOQ",
                "La cantidad mínima de compra no es válida.");
    }

    private void validateTiers(List<SupplierCostTierRequest> tiers, Unit unit) {
        if (tiers == null) {
            throw businessError("SUPPLIER_COST_TIER_INVALID", "La lista de escalas es requerida.");
        }
        Set<BigDecimal> quantities = new HashSet<>();
        for (SupplierCostTierRequest tier : tiers) {
            if (tier == null) {
                throw businessError("SUPPLIER_COST_TIER_INVALID", "Las escalas no pueden contener valores nulos.");
            }
            validateQuantity(
                    tier.minQuantity(), unit, "SUPPLIER_COST_TIER_INVALID", "La cantidad mínima no es válida.");
            validateDecimal(
                    tier.unitCost(),
                    12,
                    2,
                    true,
                    "SUPPLIER_COST_TIER_INVALID",
                    "El costo unitario no es válido.");
            BigDecimal key = tier.minQuantity().stripTrailingZeros();
            if (!quantities.add(key)) {
                throw conflict("SUPPLIER_COST_TIER_CONFLICT", "Existen cantidades mínimas duplicadas.");
            }
        }
    }

    private static void validateQuantity(BigDecimal value, Unit unit, String code, String message) {
        validateDecimal(value, 12, 3, false, code, message);
        if (!unit.getAllowsDecimals() && value.stripTrailingZeros().scale() > 0) {
            throw businessError(code, "La unidad de compra solo admite cantidades enteras.");
        }
    }

    private static void validateDecimal(
            BigDecimal value, int precision, int scale, boolean allowZero, String code, String message) {
        if (value == null || (allowZero ? value.signum() < 0 : value.signum() <= 0)) {
            throw businessError(code, message);
        }
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (fractionDigits > scale || integerDigits > precision - scale) {
            throw businessError(code, message);
        }
    }

    private List<SupplierProductResponse> responsesWithTiers(
            UUID tenantId, List<SupplierProduct> supplierProducts) {
        if (supplierProducts.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = supplierProducts.stream().map(SupplierProduct::getId).toList();
        Map<UUID, List<SupplierCostTierResponse>> tiersByProduct = new HashMap<>();
        costTierRepository.findByTenantIdAndSupplierProductIdInOrderByMinQuantityAsc(tenantId, ids).stream()
                .map(SupplierCostTierResponse::from)
                .forEach(tier -> tiersByProduct
                        .computeIfAbsent(tier.supplierProductId(), ignored -> new ArrayList<>())
                        .add(tier));
        return supplierProducts.stream()
                .map(supplierProduct -> response(
                        supplierProduct, tiersByProduct.getOrDefault(supplierProduct.getId(), List.of())))
                .toList();
    }

    private List<SupplierCostTierResponse> findTierResponses(UUID tenantId, UUID supplierProductId) {
        return costTierRepository
                .findByTenantIdAndSupplierProductIdOrderByMinQuantityAsc(tenantId, supplierProductId)
                .stream()
                .map(SupplierCostTierResponse::from)
                .toList();
    }

    private static SupplierProductResponse response(
            SupplierProduct supplierProduct, List<SupplierCostTierResponse> tiers) {
        return SupplierProductResponse.from(supplierProduct, tiers);
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static BusinessException businessError(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }
}
