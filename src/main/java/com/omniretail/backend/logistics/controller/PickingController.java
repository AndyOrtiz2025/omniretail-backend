package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.CreatePickingIncidentRequest;
import com.omniretail.backend.logistics.dto.PickingActionResponse;
import com.omniretail.backend.logistics.dto.PickingDetailResponse;
import com.omniretail.backend.logistics.dto.PickingIncidentResponse;
import com.omniretail.backend.logistics.dto.PickingLineResponse;
import com.omniretail.backend.logistics.dto.PickingQueueResponse;
import com.omniretail.backend.logistics.dto.PickingReleaseResponse;
import com.omniretail.backend.logistics.dto.ReleasePickingRequest;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Picking",
        description = "Gestión de tareas de surtido de pedidos en almacén: cola de órdenes, asignación de recolectores, registro de items surtidos, incidencias y finalización."
)
@RestController
@RequestMapping("/logistics/picking")
@RequiredArgsConstructor
public class PickingController {

    private final PickingService pickingService;

    @Operation(
            summary = "List picking queue",
            description = """
                    Recupera las órdenes de pedidos en espera de recolección física en la sucursal indicada.
                    
                    **Permisos requeridos:**
                    * `logistics.picking.read`
                    """
    )
    @GetMapping
    @RequirePermission("logistics.picking.read")
    public List<PickingQueueResponse> queue(@RequestParam UUID branchId) {
        return pickingService.getQueue(branchId);
    }

    @Operation(
            summary = "Get picking order by ID",
            description = """
                    Recupera el detalle de la tarea de picking: artículos a surtir, pasillos y racks sugeridos y progreso de recolección.
                    
                    **Permisos requeridos:**
                    * `logistics.picking.read`
                    """
    )
    @GetMapping("/{pickingOrderId}")
    @RequirePermission("logistics.picking.read")
    public PickingDetailResponse detail(
            @RequestParam UUID branchId, @PathVariable UUID pickingOrderId) {
        return pickingService.getDetail(branchId, pickingOrderId);
    }

    @Operation(
            summary = "Assign picking order",
            description = """
                    Bloquea la orden de recolección asignándola al usuario autenticado para evitar doble surtido simultáneo.
                    
                    **Permisos requeridos:**
                    * `logistics.picking.start`
                    """
    )
    @PostMapping("/{pickingOrderId}/assign")
    @RequirePermission("logistics.picking.start")
    public PickingActionResponse assign(
            @RequestParam UUID branchId, @PathVariable UUID pickingOrderId) {
        return pickingService.assign(branchId, pickingOrderId);
    }

    @Operation(
            summary = "Release picking order",
            description = """
                    Desasigna la orden de recolección con motivo justificado para regresarla a la cola general de recolectores.
                    
                    **Permisos requeridos:**
                    * `logistics.picking.start`
                    """
    )
    @PostMapping("/{pickingOrderId}/release")
    @RequirePermission("logistics.picking.start")
    public PickingReleaseResponse release(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @Valid @RequestBody ReleasePickingRequest request) {
        return pickingService.release(branchId, pickingOrderId, request.reason());
    }

    @Operation(
            summary = "Update picking item progress",
            description = """
                    Registra la cantidad recolectada de un artículo específico, validando el escaneo de código de barras, lote o serie.
                    
                    **Permisos requeridos:**
                    * `logistics.picking.start`
                    """
    )
    @PatchMapping("/{pickingOrderId}/items/{pickingItemId}")
    @RequirePermission("logistics.picking.start")
    public PickingLineResponse updateItem(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @PathVariable UUID pickingItemId,
            @Valid @RequestBody UpdatePickingItemRequest request) {
        return pickingService.updateItem(branchId, pickingOrderId, pickingItemId, request);
    }

    @Operation(
            summary = "Report picking incident",
            description = """
                    Registra una anomalía en piso (`incidentType`: `missing`, `damaged`, `invalid_lot_serial`, `quantity_difference`, `location_empty`).
                    
                    **Permisos requeridos:**
                    * `logistics.picking.start`
                    """
    )
    @PostMapping("/{pickingOrderId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("logistics.picking.start")
    public PickingIncidentResponse createIncident(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @Valid @RequestBody CreatePickingIncidentRequest request) {
        return pickingService.createIncident(branchId, pickingOrderId, request);
    }

    @Operation(
            summary = "Resolve picking incident",
            description = """
                    Marca como resuelta la incidencia de recolección permitiendo continuar el flujo de surtido.
                    
                    **Permisos requeridos:**
                    * `logistics.picking.start`
                    """
    )
    @PatchMapping("/{pickingOrderId}/incidents/{incidentId}/resolve")
    @RequirePermission("logistics.picking.start")
    public PickingIncidentResponse resolveIncident(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @PathVariable UUID incidentId) {
        return pickingService.resolveIncident(branchId, pickingOrderId, incidentId);
    }

    @Operation(
            summary = "Complete picking",
            description = """
                    Concluye la recolección física de la orden y transfiere los artículos consolidados a la estación de empaque (packing).
                    
                    **Permisos requeridos:**
                    * `logistics.picking.complete`
                    """
    )
    @PostMapping("/{pickingOrderId}/complete")
    @RequirePermission("logistics.picking.complete")
    public PickingActionResponse complete(
            @RequestParam UUID branchId, @PathVariable UUID pickingOrderId) {
        return pickingService.complete(branchId, pickingOrderId);
    }
}
