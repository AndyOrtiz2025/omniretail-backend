package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryMovementDisplayType;
import com.omniretail.backend.inventory.dto.InventoryMovementPageResponse;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.service.InventoryService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Kardex y Movimientos de Inventario",
        description = "Historial cronológico de transacciones sobre inventario (entradas por compra, salidas por venta, mermas, transferencias y ajustes manuales)."
)
@RestController
@RequestMapping("/inventory/movements")
@RequiredArgsConstructor
public class InventoryMovementController {

    private final InventoryService inventoryService;

    @Operation(
            summary = "Consultar movimientos de inventario (Kardex)",
            description = """
                    Recupera el historial paginado de movimientos de inventario con filtros por sucursal, producto, tipo de transacción y rango de fechas.
                    
                    **Filtros disponibles:**
                    * `branchId`: Identificador de la sucursal (opcional).
                    * `productId`: Identificador del producto (opcional).
                    * `type`: Tipo base de movimiento (`in`, `out`, `transfer`).
                    * `from`: Fecha inicial en formato ISO-8601 UTC (opcional).
                    * `to`: Fecha final en formato ISO-8601 UTC (opcional).
                    * `search`: Búsqueda textual por referencia, motivo o código.
                    * `displayType`: Clasificación operativa (`purchase_in`, `transfer_out`, `transfer_in`, `inventory_adjustment`, `shrinkage`, `manual_in`, `manual_out`, `sale`, `dispatch`, `return`, `void`, `store_pickup`, `in`, `out`, `adjustment`, `transfer`).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 10).
                    
                    **Permisos requeridos:**
                    * `inventory.movements.read`
                    """
    )
    @GetMapping
    @RequirePermission("inventory.movements.read")
    public InventoryMovementPageResponse search(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) InventoryMovementType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String displayType,
            @Parameter(hidden = true)
                    @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return inventoryService.searchMovements(
                branchId,
                productId,
                type,
                from,
                to,
                search,
                InventoryMovementDisplayType.parse(displayType),
                pageable);
    }
}
