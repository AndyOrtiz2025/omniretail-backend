package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CancelPurchaseOrderRequest;
import com.omniretail.backend.purchasing.dto.CreatePurchaseOrderRequest;
import com.omniretail.backend.purchasing.dto.PurchaseOrderResponse;
import com.omniretail.backend.purchasing.dto.UpdatePurchaseOrderRequest;
import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
import com.omniretail.backend.purchasing.service.PurchaseOrderService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Órdenes de Compra a Proveedores",
        description = "Ciclo de vida de órdenes de compra de mercancía: cotización/borrador, envío, aprobación ejecutiva, seguimiento y cancelación."
)
@RestController
@RequestMapping("/purchasing/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @Operation(
            summary = "Listar órdenes de compra con paginación",
            description = """
                    Recupera el listado paginado de órdenes de compra con filtros combinados por sucursal de destino, proveedor y estado administrativo.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador de la sucursal de destino (opcional).
                    * `supplierId`: Identificador del proveedor (opcional).
                    * `status`: Filtro por estado (`DRAFT`, `SUBMITTED`, `APPROVED`, `PARTIALLY_RECEIVED`, `COMPLETED`, `CANCELLED`).
                    * `page`: Número de página (base 0).
                    * `size`: Tamaño de página (por defecto 20).
                    """
    )
    @GetMapping
    public PageResponse<PurchaseOrderResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchaseOrderService.list(branchId, supplierId, status, pageable);
    }

    @Operation(
            summary = "Obtener detalle completo de una orden de compra",
            description = """
                    Recupera la orden de compra con su desglose de artículos, costos de compra pactados, impuestos, cantidades solicitadas y cantidades efectivamente recepcionadas.
                    """
    )
    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return purchaseOrderService.get(id);
    }

    @Operation(
            summary = "Crear nueva orden de compra (Borrador)",
            description = """
                    Crea un registro de orden de compra en estado preliminar (`DRAFT`) asignando proveedor, sucursal receptora y partidas.
                    
                    **Permisos requeridos:**
                    * `purchasing.orders.create`
                    """
    )
    @RequirePermission("purchasing.orders.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderResponse create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return purchaseOrderService.create(request);
    }

    @Operation(
            summary = "Actualizar partidas o datos de la orden de compra",
            description = """
                    Modifica los productos, costos o condiciones de entrega de una orden en estado borrador.
                    
                    **Permisos requeridos:**
                    * `purchasing.orders.create`
                    """
    )
    @RequirePermission("purchasing.orders.create")
    @PutMapping("/{id}")
    public PurchaseOrderResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        return purchaseOrderService.update(id, request);
    }

    @Operation(
            summary = "Enviar orden de compra para revisión",
            description = """
                    Avanza el estado de la orden de compra de borrador a enviada (`SUBMITTED`), bloqueando ediciones y solicitando autorización.
                    
                    **Permisos requeridos:**
                    * `purchasing.orders.create`
                    """
    )
    @RequirePermission("purchasing.orders.create")
    @PostMapping("/{id}/submit")
    public PurchaseOrderResponse submit(@PathVariable UUID id) {
        return purchaseOrderService.submit(id);
    }

    @Operation(
            summary = "Aprobar orden de compra",
            description = """
                    Autoriza formalmente la orden de compra (`APPROVED`), habilitándola para que el almacén pueda generar recepciones de mercancía física.
                    
                    **Permisos requeridos:**
                    * `purchasing.orders.approve`
                    """
    )
    @RequirePermission("purchasing.orders.approve")
    @PostMapping("/{id}/approve")
    public PurchaseOrderResponse approve(@PathVariable UUID id) {
        return purchaseOrderService.approve(id);
    }

    @Operation(
            summary = "Cancelar orden de compra",
            description = """
                    Cancela una orden de compra pendiente con motivo justificado, invalidando futuras recepciones asociadas.
                    """
    )
    @PostMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return purchaseOrderService.cancel(id, request);
    }
}
