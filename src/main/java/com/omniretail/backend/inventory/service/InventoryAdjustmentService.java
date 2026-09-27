package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryAdjustmentService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final InventoryStockService inventoryStockService;

    public InventoryMovementResponse adjust(InventoryAdjustmentRequest request) {
        AuthenticatedUser user = currentUser.require();
        UUID tenantId = user.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        validateOwnedReferences(tenantId, request.branchId(), request.productId());

        InventoryMovement movement = switch (request.type()) {
            case in -> inventoryStockService.incrementStock(new AddStockCommand(
                    tenantId,
                    request.branchId(),
                    request.productId(),
                    request.quantity(),
                    request.reason(),
                    request.referenceType(),
                    request.referenceId(),
                    user.userId()));
            case out -> inventoryStockService.deductStock(new DeductStockCommand(
                    tenantId,
                    request.branchId(),
                    request.productId(),
                    request.quantity(),
                    request.reason(),
                    request.referenceType(),
                    request.referenceId(),
                    user.userId()));
        };
        return InventoryMovementResponse.from(movement);
    }

    private void validateOwnedReferences(UUID tenantId, UUID branchId, UUID productId) {
        branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "BRANCH_NOT_FOUND",
                        "Sucursal no encontrada."));
        productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "PRODUCT_NOT_FOUND",
                        "Producto no encontrado."));
    }
}
