package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductPriceHistoryResponse;
import com.omniretail.backend.catalog.dto.UpdateProductPriceRequest;
import com.omniretail.backend.catalog.service.ProductPricingService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Product pricing",
        description = "Actualización del precio base de venta (`salePrice`) y consulta del histórico de variaciones de precio por auditoría."
)
@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductPricingController {

    private final ProductPricingService pricingService;

    @Operation(
            summary = "Update product sale price",
            description = """
                    Modifica el precio de venta (`salePrice`) del producto con motivo opcional (`reason`), generando automáticamente un registro inmutable en el historial de precios.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping("/{id}/price")
    @RequirePermission("catalog.products.update")
    public ProductDto updatePrice(
            @PathVariable UUID id, @Valid @RequestBody UpdateProductPriceRequest request) {
        return pricingService.updatePrice(id, request);
    }

    @Operation(
            summary = "List product price history",
            description = """
                    Recupera el histórico cronológico y paginado (`page` comenzando en 1) de modificaciones de precio de venta sufridas por el producto (precio anterior, nuevo precio, motivo, usuario responsable y fecha).
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """
    )
    @GetMapping("/{id}/price-history")
    @RequirePermission("catalog.products.read")
    public PageResponse<ProductPriceHistoryResponse> history(
            @PathVariable UUID id, Pageable pageable) {
        return pricingService.history(id, pageable);
    }
}
