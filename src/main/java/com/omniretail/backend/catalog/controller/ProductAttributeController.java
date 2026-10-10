package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductAttributeValueResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductAttributesRequest;
import com.omniretail.backend.catalog.service.ProductAttributeService;
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
        name = "Product attributes",
        description = "Asignación y consulta de especificaciones técnicas y características dinámicas por producto."
)
@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductAttributeController {

    private final ProductAttributeService attributeService;

    @Operation(
            summary = "List product attributes",
            description = """
                    Recupera la lista de valores de atributos personalizados asignados a un producto específico.
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """
    )
    @GetMapping("/{id}/attributes")
    @RequirePermission("catalog.products.read")
    public List<ProductAttributeValueResponse> get(@PathVariable UUID id) {
        return attributeService.get(id);
    }

    @Operation(
            summary = "Replace product attributes",
            description = """
                    Sobrescribe la totalidad de atributos configurados en el producto con el nuevo conjunto provisto en el cuerpo de la petición.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping("/{id}/attributes")
    @RequirePermission("catalog.products.update")
    public List<ProductAttributeValueResponse> replace(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceProductAttributesRequest request) {
        return attributeService.replace(id, request);
    }
}
