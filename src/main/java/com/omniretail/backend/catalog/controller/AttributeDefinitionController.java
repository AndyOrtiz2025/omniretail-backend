package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.AttributeDefinitionCreateRequest;
import com.omniretail.backend.catalog.dto.AttributeDefinitionResponse;
import com.omniretail.backend.catalog.dto.AttributeDefinitionUpdateRequest;
import com.omniretail.backend.catalog.service.AttributeDefinitionService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Definición de Atributos",
        description = "Configuración de atributos dinámicos para productos y variantes (talla, color, material, especificaciones técnicas)."
)
@RestController
@RequestMapping("/catalog/attributes")
@RequiredArgsConstructor
public class AttributeDefinitionController {

    private final AttributeDefinitionService definitionService;

    @Operation(
            summary = "Listar definiciones de atributos con paginación",
            description = """
                    Recupera el catálogo paginado de tipos de atributos disponibles para asociar a productos.
                    
                    **Permisos requeridos:**
                    * `catalog.attributes.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.attributes.read")
    public PageResponse<AttributeDefinitionResponse> list(Pageable pageable) {
        return definitionService.list(pageable);
    }

    @Operation(
            summary = "Crear nueva definición de atributo",
            description = """
                    Crea un nuevo tipo de atributo dinámico (ejemplo: Color, Talla, Memoria RAM).
                    
                    **Permisos requeridos:**
                    * `catalog.attributes.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.attributes.manage")
    public AttributeDefinitionResponse create(
            @Valid @RequestBody AttributeDefinitionCreateRequest request) {
        return definitionService.create(request);
    }

    @Operation(
            summary = "Actualizar definición de atributo",
            description = """
                    Modifica el nombre o configuración del atributo dinámico.
                    
                    **Permisos requeridos:**
                    * `catalog.attributes.manage`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.attributes.manage")
    public AttributeDefinitionResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody AttributeDefinitionUpdateRequest request) {
        return definitionService.update(id, request);
    }

    @Operation(
            summary = "Archivar o desactivar definición de atributo",
            description = """
                    Archiva la definición del atributo impidiendo su asignación a nuevos productos.
                    
                    **Permisos requeridos:**
                    * `catalog.attributes.manage`
                    """
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.attributes.manage")
    public void archive(@PathVariable UUID id) {
        definitionService.archive(id);
    }
}
