package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CreateSupplierProductRequest;
import com.omniretail.backend.purchasing.dto.ReplaceSupplierCostTiersRequest;
import com.omniretail.backend.purchasing.dto.SupplierCostTierResponse;
import com.omniretail.backend.purchasing.dto.SupplierProductResponse;
import com.omniretail.backend.purchasing.dto.UpdateSupplierProductRequest;
import com.omniretail.backend.purchasing.service.SupplierProductService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
        name = "Catálogo de Productos por Proveedor",
        description = "Vinculación entre productos y proveedores: costos de adquisición, SKUs de proveedor, lead times, proveedor preferido y escalas de costo por volumen."
)
@RestController
@RequestMapping("/purchasing/supplier-products")
@RequiredArgsConstructor
public class SupplierProductController {

    private final SupplierProductService supplierProductService;

    @Operation(
            summary = "Listar vinculaciones de producto-proveedor con paginación",
            description = """
                    Recupera el listado administrativo paginado de productos asociados a proveedores con filtros por proveedor, producto, estado activo y condición de preferido.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @GetMapping
    public PageResponse<SupplierProductResponse> list(
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean preferred,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return supplierProductService.listAdmin(supplierId, productId, active, preferred, pageable);
    }

    @Operation(
            summary = "Listar productos de proveedores activos (operativo)",
            description = """
                    Recupera de manera ágil los productos y costos activos asociados a un proveedor o producto para operaciones de abastecimiento en tiempo real.
                    """
    )
    @GetMapping("/active")
    public List<SupplierProductResponse> listOperational(
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID productId) {
        return supplierProductService.listOperational(supplierId, productId);
    }

    @Operation(
            summary = "Obtener detalle de relación producto-proveedor por ID",
            description = """
                    Recupera la ficha de vinculación: costo unitario base, SKU del fabricante, lead time en días y escalas de volumen.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @GetMapping("/{id}")
    public SupplierProductResponse get(@PathVariable UUID id) {
        return supplierProductService.getAdmin(id);
    }

    @Operation(
            summary = "Vincular producto a proveedor",
            description = """
                    Registra un nuevo producto en el catálogo del proveedor con su costo y condiciones de suministro.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierProductResponse create(@Valid @RequestBody CreateSupplierProductRequest request) {
        return supplierProductService.create(request);
    }

    @Operation(
            summary = "Actualizar condiciones de producto-proveedor",
            description = """
                    Actualiza el costo de compra, SKU del proveedor o tiempo de entrega pactado.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @PutMapping("/{id}")
    public SupplierProductResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateSupplierProductRequest request) {
        return supplierProductService.update(id, request);
    }

    @Operation(
            summary = "Archivar vinculación producto-proveedor",
            description = """
                    Desactiva la relación comercial con el proveedor para este artículo sin eliminar el histórico previo.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        supplierProductService.archive(id);
    }

    @Operation(
            summary = "Reactivar vinculación producto-proveedor",
            description = """
                    Vuelve a activar la relación de suministro previamente archivada.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @PostMapping("/{id}/reactivate")
    public SupplierProductResponse reactivate(@PathVariable UUID id) {
        return supplierProductService.reactivate(id);
    }

    @Operation(
            summary = "Establecer como proveedor preferido",
            description = """
                    Designa a este proveedor como la fuente de suministro primaria para el producto, utilizándose por defecto en órdenes automáticas de resurtido.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @PostMapping("/{id}/preferred")
    public SupplierProductResponse setPreferred(@PathVariable UUID id) {
        return supplierProductService.setPreferred(id);
    }

    @Operation(
            summary = "Reemplazar escalas de costo por volumen",
            description = """
                    Sobrescribe los rangos de precios de compra con descuento que ofrece el proveedor a partir de determinadas cantidades mínimas de adquisición.
                    
                    **Permisos requeridos:**
                    * `admin.suppliers.manage`
                    """
    )
    @RequirePermission("admin.suppliers.manage")
    @PutMapping("/{id}/cost-tiers")
    public List<SupplierCostTierResponse> replaceCostTiers(
            @PathVariable UUID id, @Valid @RequestBody ReplaceSupplierCostTiersRequest request) {
        return supplierProductService.replaceCostTiers(id, request);
    }
}
