package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ReplaceProductUnitConversionsRequest;
import com.omniretail.backend.catalog.dto.UnitConversionResponse;
import com.omniretail.backend.catalog.service.UnitConversionService;
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
        name = "Conversiones de Unidad por Producto",
        description = "Configuración y reemplazo de conversiones de unidades de medida particulares para un producto específico."
)
@RestController
@RequestMapping("/catalog/products/{productId}/unit-conversions")
@RequiredArgsConstructor
public class ProductUnitConversionController {

    private final UnitConversionService service;

    @Operation(
            summary = "Listar conversiones de unidad del producto",
            description = """
                    Recupera las reglas de conversión y factores de empaque aplicables exclusivamente al producto indicado.
                    
                    **Permisos requeridos:**
                    * `catalog.units.read`
                    """
    )
    @GetMapping @RequirePermission("catalog.units.read")
    public List<UnitConversionResponse> list(@PathVariable UUID productId) { return service.listForProduct(productId); }

    @Operation(
            summary = "Reemplazar conversiones de unidad del producto",
            description = """
                    Sobrescribe la lista de equivalencias de conversión de unidades asignadas al producto.
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @PutMapping @RequirePermission("catalog.units.manage")
    public List<UnitConversionResponse> replace(@PathVariable UUID productId,
            @Valid @RequestBody ReplaceProductUnitConversionsRequest request) { return service.replaceForProduct(productId, request); }
}
