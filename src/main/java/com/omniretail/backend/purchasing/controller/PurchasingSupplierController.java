package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierDetailResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierIncidentResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierProductResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierResponse;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierSummaryResponse;
import com.omniretail.backend.purchasing.service.PurchasingSupplierService;
import com.omniretail.backend.shared.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Gestión de Proveedores de Compras",
        description = "Consulta de proveedores activos para compras, catálogo de productos provistos, métricas de incidentes y fichas técnicas."
)
@RestController
@RequestMapping("/purchasing/suppliers")
@RequiredArgsConstructor
public class PurchasingSupplierController {

    private final PurchasingSupplierService purchasingSupplierService;

    @Operation(
            summary = "Listar proveedores activos (selectores)",
            description = """
                    Recupera la lista simplificada de proveedores activos del tenant, ideal para poblar selectores y formularios de órdenes de compra.
                    """
    )
    @GetMapping("/active")
    public List<PurchasingSupplierResponse> listActive() {
        return purchasingSupplierService.listActive();
    }

    @Operation(
            summary = "Listar proveedores con paginación y búsqueda",
            description = """
                    Recupera el padrón general de proveedores con búsqueda por razón social o identificación fiscal y filtro por estado.
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro por estado (`ACTIVE`, `INACTIVE`, `ARCHIVED`).
                    * `search`: Búsqueda textual por nombre comercial o RFC.
                    * `page`: Número de página (base 0).
                    * `size`: Tamaño de página (por defecto 20).
                    """
    )
    @GetMapping
    public PageResponse<PurchasingSupplierSummaryResponse> list(
            @RequestParam(required = false) SupplierStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchasingSupplierService.list(status, search, pageable);
    }

    @Operation(
            summary = "Listar catálogo de productos ofrecidos por el proveedor",
            description = """
                    Recupera los artículos provistos por este proveedor junto con sus números de parte (SKU del proveedor) y costos pactados.
                    """
    )
    @GetMapping("/{supplierId}/products")
    public PageResponse<PurchasingSupplierProductResponse> listProducts(
            @PathVariable UUID supplierId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchasingSupplierService.listProducts(supplierId, active, search, pageable);
    }

    @Operation(
            summary = "Consultar incidencias de recepción asociadas al proveedor",
            description = """
                    Recupera las reclamaciones y reportes de discrepancia (faltantes, piezas dañadas o productos incorrectos) registrados al recibir mercancía de este proveedor.
                    """
    )
    @GetMapping("/{supplierId}/incidents")
    public PageResponse<PurchasingSupplierIncidentResponse> listIncidents(
            @PathVariable UUID supplierId,
            @RequestParam(required = false) ReceiptIncidentStatus status,
            @RequestParam(required = false) UUID branchId,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchasingSupplierService.listIncidents(supplierId, status, branchId, pageable);
    }

    @Operation(
            summary = "Obtener detalle completo de proveedor por ID",
            description = """
                    Recupera la ficha pormenorizada del proveedor: términos de crédito, datos bancarios, contacto y resumen de órdenes de compra.
                    """
    )
    @GetMapping("/{id}")
    public PurchasingSupplierDetailResponse get(@PathVariable UUID id) {
        return purchasingSupplierService.get(id);
    }
}
