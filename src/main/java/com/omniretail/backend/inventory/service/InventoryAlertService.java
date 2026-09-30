package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.InventoryAlertResponse;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository.InventoryAlertProjection;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAlertService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final ProductInventorySettingsRepository settingsRepository;

    public PageResponse<InventoryAlertResponse> list(
            UUID branchId, InventoryAlertStatus status, Pageable requestedPageable) {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.inventory);
        requireBranchAndAccess(actor, branchId);
        if (status == InventoryAlertStatus.normal) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_ALERT_STATUS_INVALID",
                    "El estado normal no representa una alerta operativa.");
        }
        Pageable pageable = PageRequest.of(
                Math.max(requestedPageable.getPageNumber(), 0),
                Math.min(Math.max(requestedPageable.getPageSize(), 1), 100));
        return PageResponse.from(
                settingsRepository.findAlerts(
                        actor.tenantId(), branchId, status != null ? status.name() : null, pageable),
                projection -> response(branchId, projection));
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

    private static InventoryAlertResponse response(
            UUID branchId, InventoryAlertProjection projection) {
        return new InventoryAlertResponse(
                projection.getProductId(),
                branchId,
                projection.getSku(),
                projection.getProductName(),
                projection.getBaseUnitId(),
                projection.getQuantity(),
                projection.getReservedQuantity(),
                projection.getAvailableQuantity(),
                projection.getMinStock(),
                projection.getReorderPoint(),
                projection.getDefaultLocationId(),
                InventoryAlertStatus.valueOf(projection.getAlertStatus()),
                projection.getSuggestedReorder());
    }
}
