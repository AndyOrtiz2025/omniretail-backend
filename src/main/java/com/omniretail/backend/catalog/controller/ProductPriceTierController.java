package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductSalesPriceTierResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductSalesPriceTiersRequest;
import com.omniretail.backend.catalog.service.ProductSalesPriceTierService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Precios por Escala y Mayoreo",
        description = "Configuración de precios escalonados por volumen y reglas de mayoreo por producto."
)
@RestController
@RequestMapping("/catalog/products/{productId}/price-tiers")
@RequiredArgsConstructor
public class ProductPriceTierController {

    private final ProductSalesPriceTierService service;

    @Operation(
            summary = "Listar niveles de precios por volumen de un producto",
            description = """
                    Recupera los rangos de volumen y precios especiales (mayoreo, distribuidor) configurados para el producto indicado.
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.products.read")
    public List<ProductSalesPriceTierResponse> list(@PathVariable UUID productId) {
        return service.list(productId);
    }

    @Operation(
            summary = "Reemplazar niveles de precios por volumen de un producto",
            description = """
                    Sobrescribe la matriz de precios escalonados (cantidad mínima y precio unitario) para el producto especificado.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping
    @RequirePermission("catalog.products.update")
    public List<ProductSalesPriceTierResponse> replace(
            @PathVariable UUID productId,
            @Valid @RequestBody ReplaceProductSalesPriceTiersRequest request) {
        return service.replace(productId, request);
    }
}
