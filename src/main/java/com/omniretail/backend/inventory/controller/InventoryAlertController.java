package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryAlertResponse;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.service.InventoryAlertService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Inventory alerts",
        description = "Notificaciones y monitoreo de productos por desabastecimiento, nivel crítico o cercanía al stock mínimo."
)
@RestController
@RequestMapping("/inventory/alerts")
@RequiredArgsConstructor
public class InventoryAlertController {

    private final InventoryAlertService inventoryAlertService;

    @Operation(
            summary = "List inventory alerts",
            description = """
                    Recupera el listado paginado de alertas de stock vigentes en una sucursal, con filtro opcional por nivel de severidad.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador único de la sucursal (obligatorio).
                    * `status`: Filtro opcional por severidad (`out_of_stock`, `critical`, `near_minimum`, `normal`).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping
    @RequirePermission("inventory.stock.read")
    public PageResponse<InventoryAlertResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) InventoryAlertStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryAlertService.list(branchId, status, pageable);
    }
}
