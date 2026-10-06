package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.purchasing.dto.CancelPurchaseOrderRequest;
import com.omniretail.backend.purchasing.dto.CreatePurchaseOrderRequest;
import com.omniretail.backend.purchasing.dto.PurchaseOrderItemRequest;
import com.omniretail.backend.purchasing.dto.PurchaseOrderItemResponse;
import com.omniretail.backend.purchasing.dto.PurchaseOrderResponse;
import com.omniretail.backend.purchasing.dto.UpdatePurchaseOrderRequest;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
import com.omniretail.backend.purchasing.entity.SupplierCostTier;
import com.omniretail.backend.purchasing.entity.SupplierProduct;
import com.omniretail.backend.purchasing.repository.PurchaseOrderItemRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderRepository;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class PurchaseOrderService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private static final List<String> READ_PERMISSIONS = List.of(
            "purchasing.orders.read", "purchasing.orders.create", "purchasing.orders.approve");
    private static final List<String> DRAFT_CANCEL_PERMISSIONS = List.of(
            "purchasing.orders.create", "purchasing.orders.approve");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierCostTierRepository supplierCostTierRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final UnitRepository unitRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final BusinessConfigService businessConfigService;
    private final DocumentCounterService documentCounterService;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final PermissionResolver permissionResolver;
    private final CurrentUser currentUser;
    private final PurchaseOrderEmailNotifier purchaseOrderEmailNotifier;

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderResponse> list(
            UUID branchId,
            UUID supplierId,
            PurchaseOrderStatus status,
            Pageable requestedPageable) {
        AuthenticatedUser actor = currentUser.require();
        requireAnyPermission(actor, READ_PERMISSIONS);
        UUID tenantId = actor.tenantId();
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (branchId != null) {
            requireBranch(tenantId, branchId);
            requireBranchAccess(access, branchId);
        }
        if (supplierId != null) {
            requireSupplier(tenantId, supplierId);
        }

        Pageable pageable = safePageable(requestedPageable);
        Page<PurchaseOrder> page;
        if (access.allBranches()) {
            page = purchaseOrderRepository.findPage(
                    tenantId, branchId, supplierId, status, pageable);
        } else if (access.branchIds().isEmpty()) {
            page = Page.empty(pageable);
        } else {
            page = purchaseOrderRepository.findPageForBranches(
                    tenantId, access.branchIds(), branchId, supplierId, status, pageable);
        }

        Map<UUID, List<PurchaseOrderItem>> itemsByOrder = findItemsByOrder(tenantId, page.getContent());
        SuggestedCosts suggestedCosts = suggestedCosts(tenantId, page.getContent(), itemsByOrder);
        return PageResponse.from(page, order -> response(
                order,
                itemsByOrder.getOrDefault(order.getId(), List.of()),
                suggestedCosts));
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        requireAnyPermission(actor, READ_PERMISSIONS);
        PurchaseOrder order = requireOrder(actor.tenantId(), id);
        requireBranchAccess(branchAccessResolver.resolve(actor), order.getBranchId());
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(actor.tenantId(), id);
        return response(order, items, suggestedCosts(actor.tenantId(), List.of(order), Map.of(id, items)));
    }

    public PurchaseOrderResponse create(CreatePurchaseOrderRequest request) {
        AuthenticatedUser actor = requirePurchasingActor();
        UUID tenantId = actor.tenantId();
        Branch branch = requireActiveBranch(tenantId, request.branchId());
        requireBranchAccess(branchAccessResolver.resolve(actor), branch.getId());
        Supplier supplier = requireSupplier(tenantId, request.supplierId());
        List<ResolvedItem> resolvedItems = resolveDraftItems(
                tenantId, supplier.getId(), request.items(), Set.of());

        PurchaseOrder order = PurchaseOrder.builder()
                .branchId(branch.getId())
                .number(documentCounterService.nextPurchaseOrderNumber(tenantId))
                .supplierId(supplier.getId())
                .supplierNameSnapshot(supplier.getName())
                .status(PurchaseOrderStatus.draft)
                .expectedDate(request.expectedDate())
                .notes(normalize(request.notes()))
                .subtotal(totalOf(resolvedItems))
                .total(totalOf(resolvedItems))
                .createdByUserId(actor.userId())
                .build();
        order.setTenantId(tenantId);
        PurchaseOrder savedOrder = purchaseOrderRepository.saveAndFlush(order);
        List<PurchaseOrderItem> savedItems = saveResolvedItems(tenantId, savedOrder.getId(), resolvedItems);
        return response(
                savedOrder,
                savedItems,
                suggestedCostsFromResolved(savedItems, resolvedItems));
    }

    public PurchaseOrderResponse update(UUID id, UpdatePurchaseOrderRequest request) {
        AuthenticatedUser actor = requirePurchasingActor();
        UUID tenantId = actor.tenantId();
        PurchaseOrder order = requireOrderForUpdate(tenantId, id);
        BranchAccess access = branchAccessResolver.resolve(actor);
        requireBranchAccess(access, order.getBranchId());
        requireStatus(order, PurchaseOrderStatus.draft);

        Branch branch = requireActiveBranch(tenantId, request.branchId());
        requireBranchAccess(access, branch.getId());
        Supplier supplier = requireSupplier(tenantId, request.supplierId());
        List<PurchaseOrderItem> existingItems = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, id);
        Set<UUID> reusableSupplierProductIds = order.getSupplierId().equals(supplier.getId())
                ? existingItems.stream()
                        .map(PurchaseOrderItem::getSupplierProductId)
                        .collect(Collectors.toSet())
                : Set.of();

        // Toda la coleccion se resuelve antes de borrar una sola linea.
        List<ResolvedItem> resolvedItems = resolveDraftItems(
                tenantId, supplier.getId(), request.items(), reusableSupplierProductIds);
        BigDecimal total = totalOf(resolvedItems);

        order.setBranchId(branch.getId());
        order.setSupplierId(supplier.getId());
        order.setSupplierNameSnapshot(supplier.getName());
        order.setExpectedDate(request.expectedDate());
        order.setNotes(normalize(request.notes()));
        order.setSubtotal(total);
        order.setTotal(total);
        purchaseOrderItemRepository.deleteByTenantIdAndPurchaseOrderId(tenantId, id);
        List<PurchaseOrderItem> savedItems = saveResolvedItems(tenantId, id, resolvedItems);
        PurchaseOrder savedOrder = purchaseOrderRepository.saveAndFlush(order);
        return response(
                savedOrder,
                savedItems,
                suggestedCostsFromResolved(savedItems, resolvedItems));
    }

    public PurchaseOrderResponse submit(UUID id) {
        AuthenticatedUser actor = requirePurchasingActor();
        UUID tenantId = actor.tenantId();
        PurchaseOrder order = requireOrderForUpdate(tenantId, id);
        requireBranchAccess(branchAccessResolver.resolve(actor), order.getBranchId());
        requireStatus(order, PurchaseOrderStatus.draft);
        requireActiveBranch(tenantId, order.getBranchId());
        Supplier supplier = requireActiveSupplier(tenantId, order.getSupplierId());
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, id);
        if (items.isEmpty()) {
            throw businessError(
                    "PURCHASE_ORDER_ITEMS_REQUIRED", "La orden debe contener al menos un producto.");
        }

        BusinessConfigResponse config = businessConfigService.getConfig();
        for (PurchaseOrderItem item : items) {
            refreshAndValidateForSubmit(tenantId, order, item, config.supportsUnitsAndPackaging());
        }
        BigDecimal total = totalOfItems(items);
        order.setSupplierNameSnapshot(supplier.getName());
        order.setSubtotal(total);
        order.setTotal(total);
        order.setStatus(PurchaseOrderStatus.pending_approval);
        purchaseOrderItemRepository.saveAllAndFlush(items);
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        return response(saved, items, SuggestedCosts.NONE);
    }

    public PurchaseOrderResponse approve(UUID id) {
        AuthenticatedUser actor = requirePurchasingActor();
        PurchaseOrder order = requireOrderForUpdate(actor.tenantId(), id);
        requireBranchAccess(branchAccessResolver.resolve(actor), order.getBranchId());
        requireStatus(order, PurchaseOrderStatus.pending_approval);

        order.setStatus(PurchaseOrderStatus.approved);
        order.setApprovedByUserId(actor.userId());
        order.setApprovedAt(Instant.now());
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(actor.tenantId(), id);

        Supplier supplier = supplierRepository.findByTenantIdAndId(actor.tenantId(), saved.getSupplierId())
                .orElse(null);
        Branch branch = branchRepository.findByTenantIdAndId(actor.tenantId(), saved.getBranchId())
                .orElse(null);
        purchaseOrderEmailNotifier.notifyOrderApproved(saved, supplier, branch, items);

        return response(saved, items, SuggestedCosts.NONE);
    }

    public PurchaseOrderResponse cancel(UUID id, CancelPurchaseOrderRequest request) {
        AuthenticatedUser actor = requirePurchasingActor();
        PurchaseOrder order = requireOrderForUpdate(actor.tenantId(), id);
        requireBranchAccess(branchAccessResolver.resolve(actor), order.getBranchId());
        requireCancellationPermission(actor, order.getStatus());
        String reason = normalize(request.reason());
        if (reason == null) {
            throw businessError(
                    "PURCHASE_ORDER_CANCELLATION_REASON_REQUIRED",
                    "El motivo de cancelacion es obligatorio.");
        }

        order.setStatus(PurchaseOrderStatus.cancelled);
        order.setCancellationReason(reason);
        order.setCancelledByUserId(actor.userId());
        order.setCancelledAt(Instant.now());
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(actor.tenantId(), id);
        return response(saved, items, SuggestedCosts.NONE);
    }

    private List<ResolvedItem> resolveDraftItems(
            UUID tenantId,
            UUID supplierId,
            List<PurchaseOrderItemRequest> requests,
            Set<UUID> reusableSupplierProductIds) {
        List<ResolvedItem> result = new ArrayList<>(requests.size());
        for (PurchaseOrderItemRequest request : requests) {
            SupplierProduct supplierProduct = supplierProductRepository
                    .findByTenantIdAndSupplierIdAndProductId(tenantId, supplierId, request.productId())
                    .orElseThrow(PurchaseOrderService::supplierProductNotFound);
            if (!supplierProduct.getActive()
                    && !reusableSupplierProductIds.contains(supplierProduct.getId())) {
                throw businessError(
                        "PURCHASE_ORDER_SUPPLIER_PRODUCT_INACTIVE",
                        "La relacion comercial del producto no esta activa.");
            }
            Product product = requireProduct(tenantId, request.productId());
            requireDirectlyPurchasable(product);
            Unit purchaseUnit = requireUnit(tenantId, supplierProduct.getPurchaseUnitId());
            Unit baseUnit = requireUnit(tenantId, product.getBaseUnitId());
            validateFactor(product, supplierProduct);
            validateQuantity(request.quantity(), purchaseUnit, baseUnit, supplierProduct, product);
            validateMoney(request.unitCost(), "PURCHASE_ORDER_INVALID_COST", "El costo acordado no es valido.");
            BigDecimal subtotal = itemSubtotal(request.quantity(), request.unitCost());
            validateMoney(subtotal, "PURCHASE_ORDER_INVALID_COST", "El subtotal excede el valor permitido.");
            result.add(new ResolvedItem(
                    supplierProduct,
                    product,
                    purchaseUnit,
                    request.quantity(),
                    request.unitCost(),
                    subtotal,
                    null));
        }
        return attachSuggestedCosts(tenantId, result);
    }

    private void refreshAndValidateForSubmit(
            UUID tenantId,
            PurchaseOrder order,
            PurchaseOrderItem item,
            boolean packagingEnabled) {
        SupplierProduct supplierProduct = supplierProductRepository
                .findByTenantIdAndSupplierIdAndProductId(
                        tenantId, order.getSupplierId(), item.getProductId())
                .filter(found -> found.getId().equals(item.getSupplierProductId()))
                .orElseThrow(PurchaseOrderService::supplierProductNotFound);
        if (!supplierProduct.getActive()) {
            throw businessError(
                    "PURCHASE_ORDER_SUPPLIER_PRODUCT_INACTIVE",
                    "La relacion comercial del producto no esta activa.");
        }
        Product product = requireProduct(tenantId, item.getProductId());
        requireDirectlyPurchasable(product);
        if (product.getStatus() != ProductStatus.published) {
            throw businessError(
                    "PURCHASE_ORDER_PRODUCT_INVALID", "El producto no esta publicado para compras.");
        }
        Unit purchaseUnit = requireUnit(tenantId, supplierProduct.getPurchaseUnitId());
        if (purchaseUnit.getStatus() != UnitStatus.active) {
            throw businessError(
                    "PURCHASE_ORDER_UNIT_INVALID", "La unidad de compra no esta activa.");
        }
        Unit baseUnit = requireUnit(tenantId, product.getBaseUnitId());
        validateFactor(product, supplierProduct);
        boolean usesBaseUnit = product.getBaseUnitId().equals(supplierProduct.getPurchaseUnitId());
        if (!packagingEnabled
                && (!usesBaseUnit || supplierProduct.getPurchaseToBaseFactor().compareTo(ONE) != 0)) {
            throw businessError(
                    "BUSINESS_CAPABILITY_DISABLED",
                    "Las unidades alternativas requieren habilitar unidades y empaques en el negocio.");
        }
        validateQuantity(item.getQuantity(), purchaseUnit, baseUnit, supplierProduct, product);
        if (item.getQuantity().compareTo(supplierProduct.getMinimumOrderQuantity()) < 0) {
            throw businessError(
                    "PURCHASE_ORDER_MOQ_NOT_MET", "La cantidad no cumple el minimo de compra del proveedor.");
        }
        validateMoney(item.getUnitCost(), "PURCHASE_ORDER_INVALID_COST", "El costo acordado no es valido.");

        item.setSupplierProductId(supplierProduct.getId());
        item.setProductId(product.getId());
        item.setProductNameSnapshot(product.getName());
        item.setProductSkuSnapshot(product.getSku());
        item.setSupplierSkuSnapshot(supplierProduct.getSupplierSku());
        item.setUnitId(purchaseUnit.getId());
        item.setUnitSymbolSnapshot(purchaseUnit.getSymbol());
        item.setPurchaseToBaseFactor(supplierProduct.getPurchaseToBaseFactor());
        item.setSubtotal(itemSubtotal(item.getQuantity(), item.getUnitCost()));
    }

    private void requireCancellationPermission(AuthenticatedUser actor, PurchaseOrderStatus status) {
        switch (status) {
            case draft, pending_approval -> requireAnyPermission(actor, DRAFT_CANCEL_PERMISSIONS);
            case approved -> requireAnyPermission(actor, List.of("purchasing.orders.approve"));
            case cancelled, sent, partially_received, received -> throw invalidStatus();
        }
    }

    private static void requireDirectlyPurchasable(Product product) {
        if (product.getProductType() == ProductType.kit) {
            throw businessError("PURCHASE_ORDER_KIT_NOT_ALLOWED", "Los kits no se compran directamente a proveedores.");
        }
    }

    private AuthenticatedUser requirePurchasingActor() {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.purchasing);
        return actor;
    }

    private void requireAnyPermission(AuthenticatedUser actor, Collection<String> permissions) {
        if (actor.roleId() == null
                || permissions.stream().noneMatch(permission -> permissionResolver.hasPermission(
                        actor.tenantId(), actor.roleId(), permission))) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED", "No tienes permiso para realizar esta operacion de compras.");
        }
    }

    private PurchaseOrder requireOrder(UUID tenantId, UUID id) {
        return purchaseOrderRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(PurchaseOrderService::orderNotFound);
    }

    private PurchaseOrder requireOrderForUpdate(UUID tenantId, UUID id) {
        return purchaseOrderRepository
                .findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(PurchaseOrderService::orderNotFound);
    }

    private Branch requireBranch(UUID tenantId, UUID branchId) {
        return branchRepository
                .findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
    }

    private Branch requireActiveBranch(UUID tenantId, UUID branchId) {
        Branch branch = requireBranch(tenantId, branchId);
        if (branch.getStatus() != BranchStatus.active) {
            throw businessError("PURCHASE_ORDER_BRANCH_INVALID", "La sucursal no esta activa.");
        }
        return branch;
    }

    private Supplier requireSupplier(UUID tenantId, UUID supplierId) {
        return supplierRepository
                .findByTenantIdAndId(tenantId, supplierId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));
    }

    private Supplier requireActiveSupplier(UUID tenantId, UUID supplierId) {
        Supplier supplier = requireSupplier(tenantId, supplierId);
        if (supplier.getStatus() != SupplierStatus.active) {
            throw businessError(
                    "PURCHASE_ORDER_SUPPLIER_INACTIVE", "El proveedor no esta activo para compras.");
        }
        return supplier;
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository
                .findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "PURCHASE_ORDER_PRODUCT_NOT_FOUND",
                        "Producto no encontrado."));
    }

    private Unit requireUnit(UUID tenantId, UUID unitId) {
        return unitRepository
                .findByTenantIdAndId(tenantId, unitId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PURCHASE_ORDER_UNIT_NOT_FOUND", "Unidad no encontrada."));
    }

    private static void requireBranchAccess(BranchAccess access, UUID branchId) {
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }

    private static void requireStatus(PurchaseOrder order, PurchaseOrderStatus expected) {
        if (order.getStatus() != expected) {
            throw invalidStatus();
        }
    }

    private static void validateFactor(Product product, SupplierProduct supplierProduct) {
        BigDecimal factor = supplierProduct.getPurchaseToBaseFactor();
        if (!fitsDecimal(factor, 18, 6) || factor.signum() <= 0) {
            throw businessError("PURCHASE_ORDER_INVALID_FACTOR", "El factor de compra no es valido.");
        }
        if (product.getBaseUnitId().equals(supplierProduct.getPurchaseUnitId())
                && factor.compareTo(ONE) != 0) {
            throw businessError(
                    "PURCHASE_ORDER_INVALID_FACTOR", "El factor debe ser 1 para la unidad base.");
        }
    }

    private static void validateQuantity(
            BigDecimal quantity,
            Unit purchaseUnit,
            Unit baseUnit,
            SupplierProduct supplierProduct,
            Product product) {
        if (!fitsDecimal(quantity, 12, 3) || quantity.signum() <= 0) {
            throw businessError(
                    "PURCHASE_ORDER_INVALID_QUANTITY", "La cantidad de compra no es valida.");
        }
        if (!purchaseUnit.getAllowsDecimals() && !isInteger(quantity)) {
            throw businessError(
                    "PURCHASE_ORDER_INVALID_QUANTITY", "La unidad de compra solo admite cantidades enteras.");
        }
        BigDecimal baseQuantity = quantity.multiply(supplierProduct.getPurchaseToBaseFactor());
        if ((!baseUnit.getAllowsDecimals() || Boolean.TRUE.equals(product.getTrackingSerial()))
                && !isInteger(baseQuantity)) {
            throw businessError(
                    "PURCHASE_ORDER_INVALID_QUANTITY",
                    "La cantidad convertida a la unidad base debe ser entera.");
        }
    }

    private static void validateMoney(BigDecimal value, String code, String message) {
        if (value == null || value.signum() < 0 || !fitsDecimal(value, 12, 2)) {
            throw businessError(code, message);
        }
    }

    private static boolean fitsDecimal(BigDecimal value, int precision, int scale) {
        if (value == null) {
            return false;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionDigits <= scale && integerDigits <= precision - scale;
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal itemSubtotal(BigDecimal quantity, BigDecimal unitCost) {
        return quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal totalOf(List<ResolvedItem> items) {
        BigDecimal total = items.stream()
                .map(ResolvedItem::subtotal)
                .reduce(ZERO_MONEY, BigDecimal::add);
        validateMoney(total, "PURCHASE_ORDER_INVALID_COST", "El total excede el valor permitido.");
        return total;
    }

    private static BigDecimal totalOfItems(List<PurchaseOrderItem> items) {
        BigDecimal total = items.stream()
                .map(PurchaseOrderItem::getSubtotal)
                .reduce(ZERO_MONEY, BigDecimal::add);
        validateMoney(total, "PURCHASE_ORDER_INVALID_COST", "El total excede el valor permitido.");
        return total;
    }

    private List<ResolvedItem> attachSuggestedCosts(UUID tenantId, List<ResolvedItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Set<UUID> supplierProductIds = items.stream()
                .map(item -> item.supplierProduct().getId())
                .collect(Collectors.toSet());
        Map<UUID, List<SupplierCostTier>> tiers = supplierCostTierRepository
                .findByTenantIdAndSupplierProductIdInOrderByMinQuantityAsc(tenantId, supplierProductIds)
                .stream()
                .collect(Collectors.groupingBy(
                        SupplierCostTier::getSupplierProductId,
                        HashMap::new,
                        Collectors.toList()));
        return items.stream()
                .map(item -> new ResolvedItem(
                        item.supplierProduct(),
                        item.product(),
                        item.purchaseUnit(),
                        item.quantity(),
                        item.unitCost(),
                        item.subtotal(),
                        tiers.getOrDefault(item.supplierProduct().getId(), List.of()).stream()
                                .filter(tier -> tier.getMinQuantity().compareTo(item.quantity()) <= 0)
                                .reduce((first, second) -> second)
                                .map(SupplierCostTier::getUnitCost)
                                .orElse(item.supplierProduct().getLastCost())))
                .toList();
    }

    private List<PurchaseOrderItem> saveResolvedItems(
            UUID tenantId, UUID orderId, List<ResolvedItem> resolvedItems) {
        if (resolvedItems.isEmpty()) {
            return List.of();
        }
        List<PurchaseOrderItem> items = resolvedItems.stream()
                .map(resolved -> PurchaseOrderItem.builder()
                        .tenantId(tenantId)
                        .purchaseOrderId(orderId)
                        .supplierProductId(resolved.supplierProduct().getId())
                        .productId(resolved.product().getId())
                        .productNameSnapshot(resolved.product().getName())
                        .productSkuSnapshot(resolved.product().getSku())
                        .supplierSkuSnapshot(resolved.supplierProduct().getSupplierSku())
                        .quantity(resolved.quantity())
                        .unitId(resolved.purchaseUnit().getId())
                        .unitSymbolSnapshot(resolved.purchaseUnit().getSymbol())
                        .purchaseToBaseFactor(resolved.supplierProduct().getPurchaseToBaseFactor())
                        .unitCost(resolved.unitCost())
                        .subtotal(resolved.subtotal())
                        .build())
                .toList();
        return purchaseOrderItemRepository.saveAllAndFlush(items);
    }

    private Map<UUID, List<PurchaseOrderItem>> findItemsByOrder(
            UUID tenantId, List<PurchaseOrder> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<UUID> orderIds = orders.stream().map(PurchaseOrder::getId).toList();
        return purchaseOrderItemRepository
                .findForOrders(tenantId, orderIds)
                .stream()
                .collect(Collectors.groupingBy(
                        PurchaseOrderItem::getPurchaseOrderId,
                        HashMap::new,
                        Collectors.toList()));
    }

    private SuggestedCosts suggestedCosts(
            UUID tenantId,
            List<PurchaseOrder> orders,
            Map<UUID, List<PurchaseOrderItem>> itemsByOrder) {
        Set<UUID> draftOrderIds = orders.stream()
                .filter(order -> order.getStatus() == PurchaseOrderStatus.draft)
                .map(PurchaseOrder::getId)
                .collect(Collectors.toSet());
        if (draftOrderIds.isEmpty()) {
            return SuggestedCosts.NONE;
        }
        List<PurchaseOrderItem> draftItems = draftOrderIds.stream()
                .flatMap(orderId -> itemsByOrder.getOrDefault(orderId, List.of()).stream())
                .toList();
        Set<UUID> supplierProductIds = draftItems.stream()
                .map(PurchaseOrderItem::getSupplierProductId)
                .collect(Collectors.toSet());
        if (supplierProductIds.isEmpty()) {
            return SuggestedCosts.NONE;
        }
        Map<UUID, SupplierProduct> supplierProducts = supplierProductRepository
                .findByTenantIdAndIdIn(tenantId, supplierProductIds)
                .stream()
                .collect(Collectors.toMap(SupplierProduct::getId, Function.identity()));
        Map<UUID, List<SupplierCostTier>> tiers = supplierCostTierRepository
                .findByTenantIdAndSupplierProductIdInOrderByMinQuantityAsc(tenantId, supplierProductIds)
                .stream()
                .collect(Collectors.groupingBy(
                        SupplierCostTier::getSupplierProductId,
                        HashMap::new,
                        Collectors.toList()));
        Map<UUID, BigDecimal> byItem = new HashMap<>();
        for (PurchaseOrderItem item : draftItems) {
            SupplierProduct supplierProduct = supplierProducts.get(item.getSupplierProductId());
            if (supplierProduct == null) {
                continue;
            }
            BigDecimal suggested = tiers.getOrDefault(supplierProduct.getId(), List.of()).stream()
                    .filter(tier -> tier.getMinQuantity().compareTo(item.getQuantity()) <= 0)
                    .reduce((first, second) -> second)
                    .map(SupplierCostTier::getUnitCost)
                    .orElse(supplierProduct.getLastCost());
            byItem.put(item.getId(), suggested);
        }
        return new SuggestedCosts(byItem);
    }

    private static SuggestedCosts suggestedCostsFromResolved(
            List<PurchaseOrderItem> savedItems, List<ResolvedItem> resolvedItems) {
        Map<UUID, BigDecimal> byItem = new HashMap<>();
        for (int index = 0; index < savedItems.size(); index++) {
            byItem.put(savedItems.get(index).getId(), resolvedItems.get(index).suggestedUnitCost());
        }
        return new SuggestedCosts(byItem);
    }

    private static PurchaseOrderResponse response(
            PurchaseOrder order,
            List<PurchaseOrderItem> items,
            SuggestedCosts suggestedCosts) {
        List<PurchaseOrderItemResponse> itemResponses = items.stream()
                .map(item -> new PurchaseOrderItemResponse(
                        item.getId(),
                        item.getSupplierProductId(),
                        item.getProductId(),
                        item.getProductNameSnapshot(),
                        item.getProductSkuSnapshot(),
                        item.getSupplierSkuSnapshot(),
                        item.getQuantity(),
                        item.getUnitId(),
                        item.getUnitSymbolSnapshot(),
                        item.getPurchaseToBaseFactor(),
                        item.getUnitCost(),
                        order.getStatus() == PurchaseOrderStatus.draft
                                ? suggestedCosts.byItem().get(item.getId())
                                : null,
                        item.getSubtotal()))
                .toList();
        return new PurchaseOrderResponse(
                order.getId(),
                order.getBranchId(),
                order.getNumber(),
                order.getSupplierId(),
                order.getSupplierNameSnapshot(),
                order.getStatus(),
                order.getExpectedDate(),
                order.getNotes(),
                order.getSubtotal(),
                order.getTotal(),
                order.getCreatedByUserId(),
                order.getApprovedByUserId(),
                order.getApprovedAt(),
                order.getCancellationReason(),
                order.getCancelledByUserId(),
                order.getCancelledAt(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                itemResponses);
    }

    private static Pageable safePageable(Pageable requested) {
        int size = Math.min(Math.max(requested.getPageSize(), 1), 100);
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static BusinessException orderNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "PURCHASE_ORDER_NOT_FOUND", "Orden de compra no encontrada.");
    }

    private static BusinessException supplierProductNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "PURCHASE_ORDER_SUPPLIER_PRODUCT_NOT_FOUND",
                "El proveedor no tiene configurado el producto solicitado.");
    }

    private static BusinessException invalidStatus() {
        return BusinessException.conflict(
                "PURCHASE_ORDER_INVALID_STATUS", "La orden no permite esta operacion en su estado actual.");
    }

    private static BusinessException businessError(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record ResolvedItem(
            SupplierProduct supplierProduct,
            Product product,
            Unit purchaseUnit,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal subtotal,
            BigDecimal suggestedUnitCost) {}

    private record SuggestedCosts(Map<UUID, BigDecimal> byItem) {
        private static final SuggestedCosts NONE = new SuggestedCosts(Map.of());
    }
}
