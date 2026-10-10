package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.ExpiringLotDto;
import com.omniretail.backend.inventory.dto.InventoryLotAvailabilityDto;
import com.omniretail.backend.inventory.dto.InventorySerialAvailabilityDto;
import com.omniretail.backend.inventory.dto.ValidateSerialsRequest;
import com.omniretail.backend.inventory.dto.ValidateSerialsResponse;
import com.omniretail.backend.inventory.service.InventorySerialValidationService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityQueryService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Trazabilidad de Lotes y Números de Serie",
        description = "Control de números de serie individuales, asignación y seguimiento de lotes con fecha de caducidad y alertas de expiración próxima."
)
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryTraceabilityController {

    private final InventoryTraceabilityQueryService queryService;
    private final InventorySerialValidationService serialValidationService;

    @Operation(
            summary = "Listar lotes disponibles con stock",
            description = """
                    Recupera los lotes con existencia positiva para un producto y sucursal, ordenados por fecha de vencimiento (FEFO) para despacho eficiente.
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/lots")
    @RequirePermission("inventory.stock.read")
    public List<InventoryLotAvailabilityDto> availableLots(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId) {
        return queryService.availableLots(branchId, productId, locationId);
    }

    @Operation(
            summary = "Listar números de serie disponibles",
            description = """
                    Recupera los números de serie en stock físico disponibles para venta o asignación del producto especificado.
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/serials")
    @RequirePermission("inventory.stock.read")
    public List<InventorySerialAvailabilityDto> availableSerials(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID lotId) {
        return queryService.availableSerials(branchId, productId, locationId, lotId);
    }

    @Operation(
            summary = "Validar números de serie escaneados",
            description = """
                    Comprueba en lote si una lista de números de serie ya existe o contiene repetidos para el producto indicado.
                    
                    **Capacidad SaaS requerida:**
                    * `inventory` o `receiving`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `inventory.stock.read`, `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @PostMapping("/serials/validate")
    public ValidateSerialsResponse validateSerials(@Valid @RequestBody ValidateSerialsRequest request) {
        return serialValidationService.validate(request);
    }

    @Operation(
            summary = "Consultar lotes próximos a vencer",
            description = """
                    Recupera el listado paginado (`page` comenzando en 1) de lotes cuya fecha de caducidad se encuentra dentro del rango de días estipulado (por defecto 30 días, entre 1 y 365) para prevenir pérdidas por vencimiento.
                    
                    **Permisos requeridos:**
                    * `inventory.stock.read`
                    """
    )
    @GetMapping("/lots/expiring")
    @RequirePermission("inventory.stock.read")
    public PageResponse<ExpiringLotDto> expiringLots(
            @RequestParam UUID branchId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID locationId,
            @Parameter(hidden = true) Pageable pageable) {
        return queryService.expiringLots(branchId, days, productId, locationId, pageable);
    }
}
