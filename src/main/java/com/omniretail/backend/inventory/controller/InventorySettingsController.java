package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.ProductInventorySettingsResponse;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.inventory.service.InventorySettingsService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Inventory settings",
        description = "Configuración de parámetros operativos por producto y sucursal: stock mínimo (`minStock`), punto de reorden (`reorderPoint`) y ubicación por defecto (`defaultLocationId`)."
)
@RestController
@RequestMapping("/inventory/settings")
@RequiredArgsConstructor
public class InventorySettingsController {

    private final InventorySettingsService inventorySettingsService;

    @Operation(
            summary = "List inventory settings",
            description = """
                    Recupera el listado paginado de parámetros y umbrales de inventario (`minStock`, `reorderPoint`, `defaultLocationId`) configurados en una sucursal.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador único de la sucursal (obligatorio).
                    * `productId`: Filtro por producto específico (opcional).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping
    @RequirePermission("inventory.stock.read")
    public PageResponse<ProductInventorySettingsResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) UUID productId,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventorySettingsService.list(branchId, productId, pageable);
    }

    @Operation(
            summary = "Get product inventory settings",
            description = """
                    Recupera los parámetros de stock mínimo (`minStock`), punto de reorden (`reorderPoint`) y ubicación por defecto (`defaultLocationId`) configurados para un producto en una sucursal específica (o `204 No Content` si no tiene configuración explícita).
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/{productId}")
    @RequirePermission("inventory.stock.read")
    public ResponseEntity<ProductInventorySettingsResponse> get(
            @PathVariable UUID productId, @RequestParam UUID branchId) {
        return inventorySettingsService
                .get(branchId, productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "Upsert product inventory settings",
            description = """
                    Configura o actualiza (upsert) el stock mínimo (`minStock`), punto de reorden (`reorderPoint`) y ubicación por defecto (`defaultLocationId`) del producto en la sucursal indicada.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping("/{productId}")
    @RequirePermission("catalog.products.update")
    public ProductInventorySettingsResponse upsert(
            @PathVariable UUID productId,
            @RequestParam UUID branchId,
            @Valid @RequestBody UpdateInventorySettingsRequest request) {
        return inventorySettingsService.upsert(branchId, productId, request);
    }
}
