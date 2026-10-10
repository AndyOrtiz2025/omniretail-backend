package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.ConfirmTransferDispatchRequest;
import com.omniretail.backend.logistics.dto.DispatchQueueResponse;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.logistics.dto.PreparedDispatchResponse;
import com.omniretail.backend.logistics.service.DispatchService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Dispatch",
        description = "Entrega de bultos a transportistas, asignación de unidades de reparto, confirmación de salida de pedidos de clientes y transferencias entre sucursales."
)
@RestController
@RequestMapping("/logistics/dispatch")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchService dispatchService;

    @Operation(
            summary = "List dispatch queue",
            description = """
                    Recupera el listado de paquetes embalados en espera de recolección por paquetería o asignación a vehículo de reparto.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.read`
                    """
    )
    @GetMapping
    @RequirePermission("logistics.dispatch.read")
    public List<DispatchQueueResponse> queue(@RequestParam UUID branchId) {
        return dispatchService.getQueue(branchId);
    }

    @Operation(
            summary = "Get order dispatch details",
            description = """
                    Recupera los datos de entrega, dirección del cliente, transportista y paquetes listos para salir.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.read`
                    """
    )
    @GetMapping("/{orderId}")
    @RequirePermission("logistics.dispatch.read")
    public DispatchResponse detail(
            @RequestParam UUID branchId, @PathVariable UUID orderId) {
        return dispatchService.getDetail(branchId, orderId);
    }

    @Operation(
            summary = "Get order dispatch readiness",
            description = """
                    Recupera el resumen consolidado de bultos, guías y validación de bultos previo a la entrega física al chofer o paquetería.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.read`
                    """
    )
    @GetMapping("/{orderId}/prepared")
    @RequirePermission("logistics.dispatch.read")
    public PreparedDispatchResponse preparedDetail(
            @RequestParam UUID branchId, @PathVariable UUID orderId) {
        return dispatchService.getPreparedDetail(branchId, orderId);
    }

    @Operation(
            summary = "Confirm order dispatch",
            description = """
                    Registra la entrega física del paquete al transportista (`transportMode`: `none`, `customer`, `own_fleet`, `third_party`), avanzando el pedido al estado `dispatched`.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.confirm`
                    """
    )
    @PostMapping("/{orderId}/confirm")
    @RequirePermission("logistics.dispatch.confirm")
    public DispatchResponse confirm(
            @RequestParam UUID branchId,
            @PathVariable UUID orderId,
            @Valid @RequestBody ConfirmDispatchRequest request) {
        return dispatchService.confirm(branchId, orderId, request);
    }

    @Operation(
            summary = "Get transfer dispatch details",
            description = """
                    Recupera los bultos y datos del traslado físico de inventario listo para salir hacia otra sucursal.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.read`
                    """
    )
    @GetMapping("/transfers/{transferId}")
    @RequirePermission("logistics.dispatch.read")
    public DispatchResponse transferDetail(
            @RequestParam UUID branchId, @PathVariable UUID transferId) {
        return dispatchService.getTransferDetail(branchId, transferId);
    }

    @Operation(
            summary = "Confirm transfer dispatch",
            description = """
                    Confirma que el vehículo ha salido con la mercancía de traspaso, colocando el despacho en `dispatched` y la transferencia en estado `inTransit`.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.confirm`
                    """
    )
    @PostMapping("/transfers/{transferId}/confirm")
    @RequirePermission("logistics.dispatch.confirm")
    public DispatchResponse confirmTransfer(
            @RequestParam UUID branchId,
            @PathVariable UUID transferId,
            @Valid @RequestBody ConfirmTransferDispatchRequest request) {
        return dispatchService.confirmTransfer(branchId, transferId, request);
    }
}
