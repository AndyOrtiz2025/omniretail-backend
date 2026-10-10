package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.CrossBranchStockDto;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.dto.InventoryKitAvailabilityResponse;
import com.omniretail.backend.inventory.dto.InventoryStockBatchRequest;
import com.omniretail.backend.inventory.dto.InventoryStockBatchResponse;
import com.omniretail.backend.inventory.dto.InventoryStockPageResponse;
import com.omniretail.backend.inventory.service.InventoryStockQueryService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Consulta de Stock y Existencias",
        description = "Monitoreo de niveles de stock en tiempo real, alertas de existencias bajas, disponibilidad de kits y consulta inter-sucursales."
)
@RestController
@RequestMapping("/inventory/stock")
@RequiredArgsConstructor
public class InventoryStockController {

    private final InventoryStockQueryService stockQueryService;

    @Operation(
            summary = "Listar existencias de inventario con filtros avanzados",
            description = """
                    Recupera el listado paginado (`page` comenzando en 1) de productos y sus niveles de stock físico, reservado y disponible en la sucursal.
                    
                    **Filtros disponibles:**
                    * `branchId`: Identificador de la sucursal (obligatorio).
                    * `search`: Búsqueda textual por nombre, SKU o código de barras (opcional).
                    * `categoryId`: Identificador de la categoría (opcional).
                    * `status`: Estado de alerta (`out_of_stock`, `critical`, `near_minimum`, `normal`).
                    * `productTypes`: Tipos de producto (`physical`, `service`, `kit`).
                    * `lowStock`: Filtrar productos con bajo stock (`true` / `false`, por defecto `false`).
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping
    @RequirePermission("inventory.stock.read")
    public InventoryStockPageResponse list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) InventoryAlertStatus status,
            @RequestParam(required = false) List<ProductType> productTypes,
            @RequestParam(defaultValue = "false") boolean lowStock,
            @Parameter(hidden = true) Pageable pageable) {
        return stockQueryService.list(branchId, search, categoryId, status, productTypes, lowStock, pageable);
    }

    @Operation(
            summary = "Consulta masiva (batch) de existencias",
            description = """
                    Consulta en una sola petición el stock disponible para un listado múltiple de IDs de productos en una sucursal determinada. Optimizado para procesos de checkout y carga rápida de catálogo POS.
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @PostMapping("/batch")
    @RequirePermission("inventory.stock.read")
    public InventoryStockBatchResponse batch(@Valid @RequestBody InventoryStockBatchRequest request) {
        return stockQueryService.batch(request);
    }

    @Operation(
            summary = "Calcular disponibilidad armable de un kit o combo",
            description = """
                    Determina cuántas unidades completas del kit pueden ensamblarse o venderse en la sucursal evaluando el stock disponible del componente más crítico (cuello de botella).
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/kits/{kitProductId}/availability")
    @RequirePermission("inventory.stock.read")
    public InventoryKitAvailabilityResponse kitAvailability(
            @PathVariable UUID kitProductId, @RequestParam UUID branchId) {
        return stockQueryService.kitAvailability(kitProductId, branchId);
    }

    @Operation(
            summary = "Consultar existencias cruzadas en otras sucursales",
            description = """
                    Recupera el inventario disponible del mismo producto en todas las demás sucursales de la empresa, facilitando transferencias o venta omnicanal.
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/branches")
    @RequirePermission("inventory.stock.read")
    public List<CrossBranchStockDto> listBranches(
            @RequestParam UUID productId, @RequestParam UUID branchId) {
        return stockQueryService.listBranches(productId, branchId);
    }
}
