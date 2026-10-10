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
                    Recupera la lista simplificada de proveedores activos (`active`) del tenant, ideal para poblar selectores y formularios de órdenes de compra.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
                    """
    )
    @GetMapping("/active")
    public List<PurchasingSupplierResponse> listActive() {
        return purchasingSupplierService.listActive();
    }

    @Operation(
            summary = "Listar proveedores con paginación y búsqueda",
            description = """
                    Recupera el padrón general de proveedores con búsqueda textual y filtro por estado.
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro por estado (`active`, `inactive`, `archived`).
                    * `search`: Búsqueda textual por nombre o código del proveedor.
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20, máx. 100).
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
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
                    Recupera de forma paginada (`page` comenzando en 1) los artículos provistos por este proveedor junto con sus números de parte (`supplierSku`), costos pactados y escalas de volumen.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
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
                    Recupera de forma paginada (`page` comenzando en 1) las incidencias de recepción (`status`: `open`, `resolved`) registradas al recibir mercancía de este proveedor.
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
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
                    Recupera la ficha operativa del proveedor para compras (datos comerciales, contacto y estado).
                    
                    **Capacidad SaaS requerida:**
                    * `purchasing`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `purchasing.orders.read`, `purchasing.orders.create` o `purchasing.orders.approve`
                    """
    )
    @GetMapping("/{id}")
    public PurchasingSupplierDetailResponse get(@PathVariable UUID id) {
        return purchasingSupplierService.get(id);
    }
}
