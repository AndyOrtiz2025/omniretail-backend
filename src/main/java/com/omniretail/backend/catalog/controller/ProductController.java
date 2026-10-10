package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductChannel;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductListDto;
import com.omniretail.backend.catalog.dto.ProductPromotionFilter;
import com.omniretail.backend.catalog.dto.ProductUpdateRequest;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.service.ProductService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
        name = "Product catalog",
        description = "Gestión central del catálogo maestro de productos (creación, edición, consulta, búsqueda avanzada, archivo y reactivación)."
)
@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @RequirePermission("catalog.products.read")
    @Operation(
            summary = "List and search products",
            description = """
                    Recupera el catálogo paginado de productos del tenant con soporte de búsqueda textual por SKU/código de barras/nombre y filtros combinados.
                    
                    **Filtros disponibles:**
                    * `search`: Búsqueda textual por nombre, SKU o código de barras.
                    * `status`: Filtro por estado (`published`, `archived`).
                    * `productType`: Tipo de producto (`physical`, `service`, `kit`).
                    * `categoryId`: Identificador de la categoría.
                    * `channels`: Canales de venta autorizados (`pos`, `ecommerce`, `mobileApp`).
                    * `promotion`: Filtro de artículos con promoción activa (`all`, `with`, `without`).
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """,
            parameters = {
                @Parameter(
                        name = "page",
                        in = ParameterIn.QUERY,
                        description = "Número de página comenzando en 1",
                        schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
                @Parameter(
                        name = "size",
                        in = ParameterIn.QUERY,
                        description = "Cantidad de elementos por página",
                        schema = @Schema(type = "integer", defaultValue = "20", minimum = "1")),
                @Parameter(
                        name = "sort",
                        in = ParameterIn.QUERY,
                        description = "Criterio de ordenamiento en formato campo,dirección",
                        example = "sku,asc",
                        schema = @Schema(type = "string"))
            })
    public PageResponse<ProductListDto> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) List<ProductChannel> channels,
            @RequestParam(defaultValue = "all") ProductPromotionFilter promotion,
            @Parameter(hidden = true) Pageable pageable) {
        if ((search == null || search.isBlank())
                && status == null
                && productType == null
                && categoryId == null
                && (channels == null || channels.isEmpty())
                && promotion == ProductPromotionFilter.all) {
            return productService.list(pageable);
        }
        return productService.list(
                search, status, productType, categoryId, channels, promotion, pageable);
    }

    @Operation(
            summary = "Create product",
            description = """
                    Registra un nuevo producto o artículo en el catálogo maestro del tenant.
                    
                    **Permisos requeridos:**
                    * `catalog.products.create`
                    """
    )
    @PostMapping
    @RequirePermission("catalog.products.create")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDto create(@Valid @RequestBody ProductCreateRequest request) {
        return productService.create(request);
    }

    @Operation(
            summary = "Get product by ID",
            description = """
                    Recupera la ficha técnica integral del producto: datos maestros, categoría, unidades de medida, precios, código de barras y estado.
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("catalog.products.read")
    public ProductDto get(@PathVariable UUID id) {
        return productService.get(id);
    }

    @Operation(
            summary = "Update product",
            description = """
                    Actualiza los campos editables del producto (nombre, descripción, categoría, estado y configuración comercial).
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.products.update")
    public ProductDto update(
            @PathVariable UUID id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(id, request);
    }

    @Operation(
            summary = "Archive product",
            description = """
                    Marca el producto como archivado/inactivo. Se preserva el historial de movimientos y ventas pasadas pero se restringe su selección en nuevas transacciones comerciales.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @DeleteMapping("/{id}")
    @RequirePermission("catalog.products.update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        productService.archive(id);
    }

    @Operation(
            summary = "Restore archived product",
            description = """
                    Reactiva un producto archivado devolviéndolo al catálogo operativo activo para venta y control de inventario.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PostMapping("/{id}/restore")
    @RequirePermission("catalog.products.update")
    public ProductDto restore(@PathVariable UUID id) {
        return productService.restore(id);
    }
}
