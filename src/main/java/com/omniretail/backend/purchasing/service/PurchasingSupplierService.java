package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierDetailResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierSummaryResponse;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
