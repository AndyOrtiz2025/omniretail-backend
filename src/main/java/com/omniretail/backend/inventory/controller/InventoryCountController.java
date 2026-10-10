package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryCountResultResponse;
import com.omniretail.backend.inventory.dto.InventoryCountSnapshotResponse;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest;
import com.omniretail.backend.inventory.service.InventoryCountService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Inventory physical counts",
        description = "Tomas físicas de inventario (auditorías y arqueos de stock), instantáneas teóricas y conciliación automática de discrepancias."
)
@RestController
@RequestMapping("/inventory/counts")
@RequiredArgsConstructor
public class InventoryCountController {

    private final InventoryCountService countService;

    @Operation(
            summary = "Get physical count snapshot",
            description = """
                    Captura el estado actual de las existencias teóricas registradas en el sistema para un producto y sucursal, sirviendo de base para la comparación con el conteo en piso.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador único de la sucursal.
                    * `productId`: Identificador del producto a auditar.
                    * `locationId`: Ubicación física específica dentro del almacén (opcional).
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/snapshot")
    @RequirePermission("inventory.stock.read")
    public InventoryCountSnapshotResponse snapshot(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId) {
        return countService.snapshot(branchId, productId, locationId);
    }

    @Operation(
            summary = "Reconcile physical inventory count",
            description = """
                    Registra las cantidades físicas reales contadas y concilia automáticamente contra el balance teórico, generando los ajustes de inventario necesarios para cuadrar el stock.
                    
                    **Permisos requeridos:**
                    * `inventory.adjustment.create`
                    """
    )
    @PostMapping("/reconcile")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("inventory.adjustment.create")
    public InventoryCountResultResponse reconcile(
            @Valid @RequestBody ReconcileInventoryCountRequest request) {
        return countService.reconcile(request);
    }
}
