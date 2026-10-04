package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierDetailResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierIncidentResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierProductResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierProductRow;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierResponse;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.repository.ReceiptIncidentRepository;
import com.omniretail.backend.purchasing.repository.SupplierCostTierRepository;
import com.omniretail.backend.purchasing.repository.SupplierProductRepository;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierSummaryResponse;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PurchasingSupplierService {

    private static final List<String> OPERATIONAL_PERMISSIONS = List.of(
            "purchasing.orders.read", "purchasing.orders.create", "purchasing.orders.approve");

    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierCostTierRepository costTierRepository;
    private final ReceiptIncidentRepository receiptIncidentRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final PermissionResolver permissionResolver;
    private final CurrentUser currentUser;

    public List<PurchasingSupplierResponse> listActive() {
        AuthenticatedUser user = currentUser.require();
        UUID tenantId = user.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.purchasing);
        requireOperationalPermission(user);

        return supplierRepository.findByTenantIdAndStatus(tenantId, SupplierStatus.active).stream()
                .map(PurchasingSupplierResponse::from)
                .toList();
    }

    /** Listado informativo paginado y filtrado en DB; sin status devuelve todos los estados. */
    public PageResponse<PurchasingSupplierSummaryResponse> list(
            SupplierStatus status, String search, Pageable requestedPageable) {
        AuthenticatedUser user = currentUser.require();
        UUID tenantId = user.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.purchasing);
        requireOperationalPermission(user);

        Pageable pageable = PageRequest.of(
                Math.max(requestedPageable.getPageNumber(), 0),
                Math.min(Math.max(requestedPageable.getPageSize(), 1), 100),
                Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
        Collection<SupplierStatus> statuses =
                status == null ? EnumSet.allOf(SupplierStatus.class) : EnumSet.of(status);
        return PageResponse.from(
                supplierRepository.search(tenantId, statuses, likePattern(search), pageable),
                PurchasingSupplierSummaryResponse::from);
    }

    public PurchasingSupplierDetailResponse get(UUID id) {
        AuthenticatedUser user = currentUser.require();
        UUID tenantId = user.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.purchasing);
        requireOperationalPermission(user);

        return supplierRepository.findByTenantIdAndId(tenantId, id)
                .map(PurchasingSupplierDetailResponse::from)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));
    }

    /**
     * Productos del proveedor, incluido su historial (proveedor, producto o unidad hoy inactivos no se
     * ocultan). Una query paginada con producto y unidad por join; los tramos de costo se leen en UN
     * segundo query por lote solo para los ids de la página.
     */
    public PageResponse<PurchasingSupplierProductResponse> listProducts(
            UUID supplierId, Boolean active, String search, Pageable requestedPageable) {
        UUID tenantId = requireOperationalAccess();
        requireSupplier(tenantId, supplierId);

        Page<PurchasingSupplierProductRow> page = supplierProductRepository.findSupplierProductRows(
                tenantId, supplierId, active, likePattern(search), unsortedPageable(requestedPageable));
        Map<UUID, List<PurchasingSupplierProductResponse.CostTier>> tiersBySupplierProduct = new HashMap<>();
        if (!page.isEmpty()) {
            List<UUID> ids = page.getContent().stream().map(PurchasingSupplierProductRow::id).toList();
            costTierRepository.findByTenantIdAndSupplierProductIdInOrderByMinQuantityAsc(tenantId, ids)
                    .forEach(tier -> tiersBySupplierProduct
                            .computeIfAbsent(tier.getSupplierProductId(), ignored -> new ArrayList<>())
                            .add(new PurchasingSupplierProductResponse.CostTier(
                                    tier.getMinQuantity(), tier.getUnitCost())));
        }
        return PageResponse.from(
                page,
                row -> PurchasingSupplierProductResponse.from(
                        row, tiersBySupplierProduct.getOrDefault(row.id(), List.of())));
    }

    /**
     * Historial de incidencias del proveedor (incidencia -> recepción -> orden). Solo sucursales a las que
     * el usuario tiene acceso; un {@code branchId} explícito se valida contra ese acceso.
     */
    public PageResponse<PurchasingSupplierIncidentResponse> listIncidents(
            UUID supplierId, ReceiptIncidentStatus status, UUID branchId, Pageable requestedPageable) {
        AuthenticatedUser user = currentUser.require();
        UUID tenantId = requireOperationalAccess(user);
        requireSupplier(tenantId, supplierId);

        BranchAccess access = branchAccessResolver.resolve(user);
        if (branchId != null && !access.allows(branchId)) {
            throw BusinessException.forbidden("BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        Pageable pageable = unsortedPageable(requestedPageable);
        Page<PurchasingSupplierIncidentResponse> page;
        if (access.allBranches()) {
            page = receiptIncidentRepository.findSupplierHistory(
                    tenantId, supplierId, branchId, status, pageable);
        } else if (access.branchIds().isEmpty()) {
            page = Page.empty(pageable);
        } else {
            page = receiptIncidentRepository.findSupplierHistoryForBranches(
                    tenantId, supplierId, access.branchIds(), branchId, status, pageable);
        }
        return PageResponse.from(page, Function.identity());
    }

    private UUID requireOperationalAccess() {
        return requireOperationalAccess(currentUser.require());
    }

    private UUID requireOperationalAccess(AuthenticatedUser user) {
        tenantCapabilityGuard.ensureTenantCapability(user.tenantId(), SaasCapability.purchasing);
        requireOperationalPermission(user);
        return user.tenantId();
    }

    private void requireSupplier(UUID tenantId, UUID supplierId) {
        supplierRepository.findByTenantIdAndId(tenantId, supplierId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));
    }

    /** El orden vive en la query (nombre/fecha + id), así que se ignora cualquier sort del cliente. */
    private static Pageable unsortedPageable(Pageable requested) {
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                Math.min(Math.max(requested.getPageSize(), 1), 100));
    }

    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
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
}
