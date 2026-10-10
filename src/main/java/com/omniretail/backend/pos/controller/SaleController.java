
package com.omniretail.backend.pos.controller;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.PosSalesHistoryPageResponse;
import com.omniretail.backend.pos.dto.SaleConfirmationResponse;
import com.omniretail.backend.pos.dto.SaleDetailResponse;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.dto.VoidSaleRequest;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.service.PosSalesHistoryService;
import com.omniretail.backend.pos.service.SaleService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "POS sales",
        description = "Cobro de tickets de venta, consulta de histórico de transacciones, detalle de tickets y anulación de ventas con reversión de inventario."
)
@RestController
@RequestMapping("/pos/sales")
@RequiredArgsConstructor
public class SaleController {

    private final SaleService service;
    private final PosSalesHistoryService historyService;

    @Operation(
            summary = "Create POS sale",
            description = """
                    Procesa una transacción de venta en punto de venta: valida stock disponible, calcula impuestos y descuentos, aplica pagos divididos (efectivo, tarjeta, vales), rebaja existencias y genera el ticket de venta.
                    
                    **Permisos requeridos:**
                    * `pos.sales.create`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("pos.sales.create")
    public SaleConfirmationResponse create(@Valid @RequestBody CreateSaleRequest request) {
        return service.create(request);
    }

    @Operation(
            summary = "List branch sales",
            description = """
                    Recupera el listado paginado de ventas de una sucursal específica con filtros por estado y marca de tiempo UTC.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador de la sucursal (obligatorio).
                    * `status`: Filtro por estado de venta (`completed`, `partially_returned`, `returned`, `cancelled`).
                    * `from`: Fecha y hora inicial en formato ISO-8601 (opcional).
                    * `to`: Fecha y hora final en formato ISO-8601 (opcional).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `pos.sales.read`
                    """
    )
    @GetMapping
    @RequirePermission("pos.sales.read")
    public Page<SaleResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pageable) {
        return service.list(branchId, status, from, to, pageable);
    }

    @Operation(
            summary = "Search sales history",
            description = """
                    Consulta paginada (`page` comenzando en 1) con búsqueda textual y filtros combinados sobre el historial de ventas del punto de venta:
                    * `branchId`: Identificador de la sucursal (obligatorio).
                    * `search`: Búsqueda textual por folio de ticket o cliente (opcional).
                    * `from` / `to`: Rango de fechas en formato `YYYY-MM-DD` (opcional).
                    * `status`: Estado comercial de la venta (`completed`, `partially_returned`, `returned`, `cancelled`).
                    * `deliveryMethod`: Método de entrega (`immediate`, `store_pickup`, `home_delivery`).
                    * `operationalStatus`: Estado operativo del pedido asociado (`pending`, `confirmed`, `preparing`, `picking`, `packing`, `ready_for_pickup`, `ready_for_dispatch`, `dispatched`, `delivered`, `cancelled`).
                    
                    **Permisos requeridos:**
                    * `pos.sales.read`
                    """
    )
    @GetMapping("/history")
    @RequirePermission("pos.sales.read")
    public PosSalesHistoryPageResponse history(
            @RequestParam UUID branchId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) DeliveryMethod deliveryMethod,
            @RequestParam(required = false) OrderStatus operationalStatus,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return historyService.search(
                branchId, search, from, to, status, deliveryMethod, operationalStatus, pageable);
    }

    @Operation(
            summary = "Get sale by ID",
            description = """
                    Recupera la información pormenorizada de una venta: partidas de productos, precios aplicados, impuestos desglosados, pagos recibidos, cambio otorgado y datos del cajero.
                    
                    **Permisos requeridos:**
                    * `pos.sales.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("pos.sales.read")
    public SaleDetailResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(
            summary = "Cancel sale",
            description = """
                    Cancela una venta previamente cobrada: devuelve automáticamente los artículos al inventario disponible de la sucursal y ajusta el balance del turno de caja.
                    
                    **Cabeceras:**
                    * `Idempotency-Key`: UUID único para garantizar que la anulación no se procese por duplicado.
                    
                    **Permisos requeridos:**
                    * `pos.sales.void`
                    """
    )
    @PostMapping("/{id}/void")
    @RequirePermission("pos.sales.void")
    public Object voidSale(
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody(required = false) VoidSaleRequest request) {
        if (idempotencyKey == null && request == null) {
            return service.voidSale(id);
        }
        if (idempotencyKey == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "El encabezado Idempotency-Key es requerido.");
        }
        if (request == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "VOID_REQUEST_REQUIRED",
                    "El motivo de anulacion es requerido.");
        }
        return service.voidSale(id, idempotencyKey, request);
    }
}
