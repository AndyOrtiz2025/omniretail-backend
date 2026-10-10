package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferReceiptResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Transferencias Físicas entre Sucursales",
        description = "Control del despacho en tránsito, recepción física y cancelación de envíos de inventario entre almacenes."
)
@RestController
@RequestMapping("/inventory/transfers")
@RequiredArgsConstructor
public class InventoryTransferController {

    private final InventoryTransferService inventoryTransferService;

    @Operation(
            summary = "Listar transferencias de inventario con paginación",
            description = """
                    Recupera el listado paginado (`page` comenzando en 1) de órdenes de traspaso entre sucursales con filtros por almacén de origen (`sourceBranchId`), almacén de destino (`destinationBranchId`) y estado operativo (`status`: `preparing`, `inTransit`, `received`, `cancelled`).
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @GetMapping
    @RequirePermission("inventory.transfers.manage")
    public PageResponse<InventoryTransferResponse> list(
            @RequestParam(required = false) UUID sourceBranchId,
            @RequestParam(required = false) UUID destinationBranchId,
            @RequestParam(required = false) InventoryTransferStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryTransferService.listTransfers(
                sourceBranchId, destinationBranchId, status, pageable);
    }

    @Operation(
            summary = "Obtener detalle de transferencia física por ID",
            description = """
                    Recupera la información completa del traspaso: artículos enviados, cantidades, lotes/series asignados y estado de entrega.
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferResponse detail(@PathVariable UUID id) {
        return inventoryTransferService.getTransfer(id);
    }

    @Operation(
            summary = "Cancelar orden de transferencia",
            description = """
                    Cancela el traspaso de mercancía y revierte el inventario apartado o en tránsito a la sucursal de origen.
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @PostMapping("/{id}/cancel")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelInventoryTransferRequest request) {
        return inventoryTransferService.cancelTransfer(id, request);
    }

    @Operation(
            summary = "Registrar recepción de transferencia en destino",
            description = """
                    Confirma el ingreso físico de la mercancía transferida en la sucursal de destino, incorporando formalmente el stock al balance disponible y registrando posibles diferencias.
                    
                    **Permisos requeridos:**
                    * `inventory.transfers.manage`
                    """
    )
    @PostMapping("/{id}/receipts")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferReceiptResponse receive(
            @PathVariable UUID id,
            @Valid @RequestBody ReceiveInventoryTransferRequest request) {
        return inventoryTransferService.receiveTransfer(id, request);
    }
}
