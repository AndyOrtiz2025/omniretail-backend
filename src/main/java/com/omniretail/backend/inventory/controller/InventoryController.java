package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryBalanceResponse;
import com.omniretail.backend.inventory.service.InventoryService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Inventory balances",
        description = "Consulta de saldos de existencias físicas, reservadas y disponibles por sucursal y producto."
)
@RestController
@RequestMapping("/inventory/balances")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(
            summary = "List inventory balances",
            description = """
                    Recupera el listado paginado de saldos de inventario para una sucursal dada, con desglose de cantidad disponible, cantidad reservada en pedidos pendientes y total en mano.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador de la sucursal (obligatorio).
                    * `productId`: Filtro opcional por producto específico.
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """,
            parameters = {
                @Parameter(
                        name = "page",
                        in = ParameterIn.QUERY,
                        description = "Número de página comenzando en 1",
                        schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
                @Parameter(
                        name = "size",
                        in = ParameterIn.QUERY,
                        description = "Cantidad de elementos por página",
                        schema = @Schema(type = "integer", defaultValue = "20", minimum = "1")),
                @Parameter(
                        name = "sort",
                        in = ParameterIn.QUERY,
                        description = "Criterio de ordenamiento en formato campo,dirección",
                        example = "productId,asc",
                        schema = @Schema(type = "string"))
            })
    @GetMapping
    @RequirePermission("inventory.stock.read")
    public PageResponse<InventoryBalanceResponse> listBalances(
            @RequestParam UUID branchId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(name = "size", required = false) Integer requestedSize,
            @Parameter(hidden = true) Pageable pageable) {
        return inventoryService.listBalances(branchId, productId, pageable, requestedSize);
    }
}
