package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductKitComponentResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductKitComponentsRequest;
import com.omniretail.backend.catalog.service.ProductKitService;
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
        name = "Componentes de Kits y Combos",
        description = "Administración de fórmulas y composición de artículos tipo Kit o Bundle (productos y cantidades integrantes)."
)
@RestController
@RequestMapping("/catalog/products/{productId}/kit-components")
@RequiredArgsConstructor
public class ProductKitController {

    private final ProductKitService service;

    @Operation(
            summary = "Listar componentes de un kit o combo",
            description = """
                    Recupera el desglose de productos que componen el kit junto con sus cantidades asociadas.
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.products.read")
    public List<ProductKitComponentResponse> list(@PathVariable UUID productId) {
        return service.list(productId);
    }

    @Operation(
            summary = "Reemplazar componentes de un kit o combo",
            description = """
                    Sobrescribe la composición del kit con los nuevos productos y cantidades provistos en la solicitud.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping
    @RequirePermission("catalog.products.update")
    public List<ProductKitComponentResponse> replace(
            @PathVariable UUID productId,
            @Valid @RequestBody ReplaceProductKitComponentsRequest request) {
        return service.replace(productId, request);
    }
}
