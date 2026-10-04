package com.omniretail.backend.inventory.service;

import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.ValidateSerialsRequest;
import com.omniretail.backend.inventory.dto.ValidateSerialsResponse;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Precheck batch de seriales para UX. No es una garantía: la validación transaccional y la
 * unicidad (tenant, producto, serial) al persistir siguen siendo la fuente autoritativa.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventorySerialValidationService {

    private static final List<String> ALLOWED_PERMISSIONS = List.of(
            "inventory.stock.read",
            "receiving.receipts.create",
            "receiving.receipts.confirm");

    private final CurrentUser currentUser;
    private final PermissionResolver permissionResolver;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final ProductRepository productRepository;
    private final InventorySerialRepository serialRepository;

    public ValidateSerialsResponse validate(ValidateSerialsRequest request) {
        AuthenticatedUser actor = currentUser.require();
        if (actor.roleId() == null
                || ALLOWED_PERMISSIONS.stream().noneMatch(permission -> permissionResolver.hasPermission(
                        actor.tenantId(), actor.roleId(), permission))) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED", "No tienes permiso para validar numeros de serie.");
        }
        // Disponible desde Inventory o Receiving; un tenant con solo Receiving también debe poder usarlo.
        tenantCapabilityGuard.ensureAnyTenantCapability(
                actor.tenantId(),
                List.of(SaasCapability.inventory, SaasCapability.receiving),
                "Esta función no está incluida en tu plan actual.");
        if (request == null || request.productId() == null
                || request.serialNumbers() == null || request.serialNumbers().isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "SERIAL_VALIDATION_INVALID_REQUEST",
                    "El producto y los numeros de serie son requeridos.");
        }
        productRepository.findByTenantIdAndId(actor.tenantId(), request.productId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));

        Set<String> unique = new LinkedHashSet<>();
        Set<String> repeated = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String raw : request.serialNumbers()) {
            String serial = InventoryTraceabilityMutationService.normalizeSerialNumber(raw);
            if (!seen.add(serial)) {
                repeated.add(serial);
            }
            unique.add(serial);
        }
        // Una sola consulta batch proyectando únicamente serial_number.
        List<String> existing = serialRepository.findExistingSerialNumbers(
                actor.tenantId(), request.productId(), unique);
        return new ValidateSerialsResponse(existing, List.copyOf(repeated));
    }
}
