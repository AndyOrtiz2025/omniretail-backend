package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.InventoryBalanceResponse;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
    private final ProductRepository productRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

    public PageResponse<InventoryBalanceResponse> listBalances(
            UUID branchId, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        validateBranch(tenantId, branchId);

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
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);

        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.badRequest(
                    "La fecha inicial no puede ser posterior a la fecha final.");
        }
        if (branchId != null) {
            validateBranch(tenantId, branchId);
        }
        if (productId != null) {
            validateProduct(tenantId, productId);
        }

        return PageResponse.from(
                inventoryMovementRepository.search(
                        tenantId, branchId, productId, type, from, to, pageable),
                InventoryMovementResponse::from);
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
}
