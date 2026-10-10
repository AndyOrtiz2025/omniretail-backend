package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizationOptionsResponse;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.service.InventoryBalanceRegularizationService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regularizacion administrativa del inventario heredado sin ubicacion. La vista previa solo requiere
 * lectura de stock (mas acceso a la sucursal); la ejecucion exige la misma autorizacion que un ajuste de
 * inventario. No se introduce un permiso nuevo ni se amplia ninguno existente.
 */
@RestController
@RequestMapping("/inventory/location-regularizations")
@RequiredArgsConstructor
public class InventoryBalanceRegularizationController {

    private final InventoryBalanceRegularizationService regularizationService;

    /**
     * {@code assign=true} evalua tambien la asignacion inicial del destino cuando el producto aun no tiene
     * ubicacion operativa; la vista previa solo sirve para ejecutar en el mismo modo.
     */
    @GetMapping("/preview")
    @RequirePermission("inventory.stock.read")
    public LegacyBalanceRegularizationPreviewResponse preview(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam UUID locationId,
            @RequestParam(defaultValue = "false") boolean assign) {
        return regularizationService.preview(branchId, productId, locationId, assign);
    }

    /** Ubicacion asignada y ubicaciones activas elegibles de la sucursal, para escoger el destino. */
    @GetMapping("/options")
    @RequirePermission("inventory.stock.read")
    public RegularizationOptionsResponse options(
            @RequestParam UUID branchId, @RequestParam UUID productId) {
        return regularizationService.options(branchId, productId);
    }

    /**
     * 201 cuando la regularizacion se aplica; 200 cuando es el reintento idempotente de una ya aplicada. Con
     * {@code assignDestination: true} la asignacion inicial exige ademas {@code catalog.products.update}
     * (verificado en el servicio solo cuando la asignacion realmente se aplica).
     */
    @PostMapping
    @RequirePermission("inventory.adjustment.create")
    public ResponseEntity<LegacyBalanceRegularizationResultResponse> regularize(
            @Valid @RequestBody RegularizeLegacyBalanceRequest request) {
        LegacyBalanceRegularizationResultResponse result = regularizationService.regularize(request);
        return ResponseEntity.status(result.idempotent() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
