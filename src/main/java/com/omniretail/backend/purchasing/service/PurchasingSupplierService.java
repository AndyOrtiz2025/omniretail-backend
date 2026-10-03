package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
