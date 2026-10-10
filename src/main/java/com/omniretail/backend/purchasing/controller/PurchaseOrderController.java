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
        name = "Purchase orders",
        description = "Ciclo de vida de órdenes de compra de mercancía: cotización/borrador, envío, aprobación ejecutiva, seguimiento y cancelación."
)
@RestController
@RequestMapping("/purchasing/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @Operation(
            summary = "List purchase orders",
            description = """
                    Recupera el listado paginado de órdenes de compra con filtros combinados por sucursal de destino, proveedor y estado administrativo.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador de la sucursal de destino (opcional).
                    * `supplierId`: Identificador del proveedor (opcional).
                    * `status`: Filtro por estado (`draft`, `pending_approval`, `approved`, `sent`, `partially_received`, `received`, `cancelled`).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20, máx. 100).
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
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
            summary = "Get purchase order by ID",
            description = """
                    Recupera la orden de compra con su desglose de artículos, costos de compra pactados, cantidades solicitadas y costos sugeridos cuando se encuentra en estado `draft`.
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
                    """
    )
    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return purchaseOrderService.get(id);
    }

    @Operation(
            summary = "Create draft purchase order",
            description = """
                    Crea un registro de orden de compra en estado preliminar (`draft`) asignando proveedor, sucursal receptora y partidas.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
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
            summary = "Update purchase order",
            description = """
                    Modifica los productos, costos o condiciones de entrega de una orden en estado `draft`.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
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
            summary = "Submit purchase order for approval",
            description = """
                    Avanza el estado de la orden de compra de `draft` a pendiente de aprobación (`pending_approval`), validando cantidades mínimas de compra (MOQ) y bloqueando ediciones posteriores.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
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
            summary = "Approve purchase order",
            description = """
                    Autoriza formalmente la orden de compra (`approved`), habilitándola para que el almacén pueda generar recepciones de mercancía física.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
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
            summary = "Cancel purchase order",
            description = """
                    Cancela una orden de compra (`cancelled`) con motivo justificado (`reason`), invalidando futuras recepciones asociadas.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
                    **Permisos requeridos:**
                    * `purchasing.orders.create` o `purchasing.orders.approve` (cuando está en `draft` o `pending_approval`).
                    * `purchasing.orders.approve` (cuando está en `approved`).
                    """
    )
    @PostMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return purchaseOrderService.cancel(id, request);
    }
}
