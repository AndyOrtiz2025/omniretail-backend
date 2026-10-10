package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.service.InventoryTransferService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Inventory transfer requests",
        description = "Flujo de peticiones de reabastecimiento entre sucursales: creación de solicitud, revisión, aprobación y rechazo."
)
@RestController
@RequestMapping("/inventory/transfer-requests")
@RequiredArgsConstructor
public class InventoryTransferRequestController {

    private final InventoryTransferService inventoryTransferService;

    @Operation(
            summary = "Create transfer request",
            description = """
                    Genera una petición formal de traspaso de mercancía desde una sucursal proveedora hacia la sucursal solicitante.
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferRequestResponse create(
            @Valid @RequestBody CreateInventoryTransferRequest request) {
        return inventoryTransferService.createRequest(request);
    }

    @Operation(
            summary = "List transfer requests",
            description = """
                    Recupera el listado paginado (`page` comenzando en 1) de solicitudes de reabastecimiento entre tiendas con filtros por sucursal solicitante (`requestingBranchId`), sucursal origen (`sourceBranchId`) y estado operativo (`status`: `requested`, `approved`, `rejected`, `cancelled`).
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @GetMapping
    @RequirePermission("inventory.transfers.manage")
    public PageResponse<InventoryTransferRequestResponse> list(
            @RequestParam(required = false) UUID requestingBranchId,
            @RequestParam(required = false) UUID sourceBranchId,
            @RequestParam(required = false) InventoryTransferRequestStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryTransferService.listRequests(
                requestingBranchId, sourceBranchId, status, pageable);
    }

    @Operation(
            summary = "Approve transfer request",
            description = """
                    Aprueba formalmente la solicitud de traspaso y genera la orden de transferencia de inventario en tránsito.
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @PostMapping("/{id}/approve")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferResponse approve(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveInventoryTransferRequest request) {
        return inventoryTransferService.approve(id, request);
    }

    @Operation(
            summary = "Reject transfer request",
            description = """
                    Rechaza la petición de reabastecimiento con motivo justificado (falta de existencias en origen, capacidad logística insuficiente, etc.).
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @PostMapping("/{id}/reject")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferRequestResponse reject(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RejectInventoryTransferRequest request) {
        return inventoryTransferService.reject(id, request);
    }

    @Operation(
            summary = "Cancel transfer request",
            description = """
                    Cancela una solicitud de transferencia previamente emitida por la sucursal solicitante antes de su aprobación.
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @PostMapping("/{id}/cancel")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferRequestResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelInventoryTransferRequest request) {
        return inventoryTransferService.cancelRequest(id, request);
    }
}
