package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.service.InventoryAdjustmentService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Ajustes Manuales de Inventario",
        description = "Ajustes de existencias por diferencias físicas, mermas, caducidades, muestras comerciales o correcciones extraordinarias."
)
@RestController
@RequestMapping("/inventory/adjustments")
@RequiredArgsConstructor
public class InventoryAdjustmentController {

    private final InventoryAdjustmentService inventoryAdjustmentService;

    @Operation(
            summary = "Realizar ajuste manual de existencias",
            description = """
                    Modifica directamente la existencia física de un producto en una sucursal específica con justificación obligatoria (positivo por sobrante o negativo por merma, rotura o pérdida).
                    
                    **Permisos requeridos:**
                    * `inventory.adjustment.create`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("inventory.adjustment.create")
    public InventoryMovementResponse adjust(
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        return inventoryAdjustmentService.adjust(request);
    }
}
