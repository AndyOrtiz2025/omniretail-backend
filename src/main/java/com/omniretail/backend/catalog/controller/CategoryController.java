package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.CategoryCreateRequest;
import com.omniretail.backend.catalog.dto.CategoryResponse;
import com.omniretail.backend.catalog.dto.CategoryUpdateRequest;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.service.CategoryService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(
        name = "Categorías de Productos",
        description = "Administración de la jerarquía de categorías del catálogo comercial, imágenes descriptivas y estados de visibilidad."
)
@RestController
@RequestMapping("/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(
            summary = "Listar categorías de productos",
            description = """
                    Recupera el listado completo de categorías registradas en el tenant, con soporte para filtrar por estado operativo.
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro opcional por estado (`ACTIVE`, `INACTIVE`).
                    
                    **Permisos requeridos:**
                    * `catalog.categories.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.categories.read")
    public List<CategoryResponse> list(
            @RequestParam(required = false) CategoryStatus status) {
        return categoryService.list(status);
    }

    @Operation(
            summary = "Obtener detalle de una categoría por ID",
            description = """
                    Recupera la información completa de una categoría (nombre, código, descripción, categoría padre e imagen asociada).
                    
                    **Permisos requeridos:**
                    * `catalog.categories.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("catalog.categories.read")
    public CategoryResponse getById(@PathVariable UUID id) {
        return categoryService.getById(id);
    }

    @Operation(
            summary = "Crear nueva categoría",
            description = """
                    Registra una nueva categoría en el catálogo del tenant.
                    
                    **Permisos requeridos:**
                    * `catalog.categories.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.categories.manage")
    public CategoryResponse create(@Valid @RequestBody CategoryCreateRequest request) {
        return categoryService.create(request);
    }

    @Operation(
            summary = "Actualizar categoría existente",
            description = """
                    Actualiza los datos descriptivos, nombre, código o jerarquía de una categoría específica.
                    
                    **Permisos requeridos:**
                    * `catalog.categories.manage`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.categories.manage")
    public CategoryResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return categoryService.update(id, request);
    }

    @Operation(
            summary = "Archivar o desactivar categoría",
            description = """
                    Cambia el estado de la categoría a inactiva o archivada, evitando que sea seleccionada en nuevos productos.
                    
                    **Permisos requeridos:**
                    * `catalog.categories.manage`
                    """
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.categories.manage")
    public void archive(@PathVariable UUID id) {
        categoryService.archive(id);
    }

    @Operation(
            summary = "Subir imagen ilustrativa de categoría",
            description = """
                    Carga un archivo de imagen (PNG, JPG, WebP) representativo de la categoría para su visualización en el storefront y POS.
                    
                    **Permisos requeridos:**
                    * `catalog.categories.manage`
                    """
    )
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("catalog.categories.manage")
    public CategoryResponse uploadImage(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return categoryService.uploadImage(id, file);
    }

    @Operation(
            summary = "Eliminar imagen ilustrativa de categoría",
            description = """
                    Elimina la imagen asociada a la categoría, retornando la entidad actualizada sin contenido multimedia.
                    
                    **Permisos requeridos:**
                    * `catalog.categories.manage`
                    """
    )
    @DeleteMapping("/{id}/image")
    @RequirePermission("catalog.categories.manage")
    public CategoryResponse deleteImage(@PathVariable UUID id) {
        return categoryService.deleteImage(id);
    }
}
