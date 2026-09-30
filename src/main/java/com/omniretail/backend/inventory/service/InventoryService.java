package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.InventoryBalanceResponse;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchRepository branchRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final ProductRepository productRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

    public PageResponse<InventoryBalanceResponse> listBalances(
            UUID branchId, Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        validateBranch(tenantId, branchId);
        requireBranchAccess(branchAccessResolver.resolve(actor), branchId);

        return PageResponse.from(
                inventoryBalanceRepository.findByTenantIdAndBranchId(
                        tenantId, branchId, pageable),
                InventoryBalanceResponse::from);
    }

    public PageResponse<InventoryMovementResponse> searchMovements(
            UUID branchId,
            UUID productId,
            InventoryMovementType type,
            Instant from,
            Instant to,
            Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);

        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.badRequest(
                    "La fecha inicial no puede ser posterior a la fecha final.");
        }
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (branchId != null) {
            validateBranch(tenantId, branchId);
            requireBranchAccess(access, branchId);
        }
        if (productId != null) {
            validateProduct(tenantId, productId);
        }

        Page<InventoryMovement> movements;
        if (branchId != null || access.allBranches()) {
            movements = inventoryMovementRepository.search(
                    tenantId, branchId, productId, type, from, to, pageable);
        } else if (access.branchIds().isEmpty()) {
            movements = Page.empty(pageable);
        } else {
            movements = inventoryMovementRepository.searchForBranches(
                    tenantId, access.branchIds(), productId, type, from, to, pageable);
        }
        return PageResponse.from(movements, InventoryMovementResponse::from);
    }

    private void validateBranch(UUID tenantId, UUID branchId) {
        branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "BRANCH_NOT_FOUND",
                        "Sucursal no encontrada."));
    }

    private void validateProduct(UUID tenantId, UUID productId) {
        productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "PRODUCT_NOT_FOUND",
                        "Producto no encontrado."));
    }

    private static void requireBranchAccess(BranchAccess access, UUID branchId) {
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }
}
