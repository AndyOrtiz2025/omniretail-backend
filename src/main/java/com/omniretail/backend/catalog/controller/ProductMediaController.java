package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductMediaCreateRequest;
import com.omniretail.backend.catalog.dto.ProductMediaResponse;
import com.omniretail.backend.catalog.dto.ProductMediaUpdateRequest;
import com.omniretail.backend.catalog.service.ProductMediaService;
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
        name = "Multimedia y Galería de Productos",
        description = "Administración de imágenes, archivos multimedia, galería de fotos y asignación de imagen principal para productos del catálogo."
)
@RestController
@RequestMapping("/catalog/products/{productId}/media")
@RequiredArgsConstructor
public class ProductMediaController {

    private final ProductMediaService service;

    @Operation(
            summary = "Listar galería multimedia de un producto",
            description = """
                    Recupera la lista de todas las imágenes y recursos multimedia asociados al producto indicado.
                    
                    **Permisos requeridos:**
                    * `catalog.products.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.products.read")
    public List<ProductMediaResponse> list(@PathVariable UUID productId) {
        return service.list(productId);
    }

    @Operation(
            summary = "Registrar medio multimedia mediante URL externa",
            description = """
                    Asocia una imagen al producto indicando una URL pública externa.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.products.update")
    public ProductMediaResponse create(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductMediaCreateRequest request) {
        return service.createExternal(productId, request);
    }

    @Operation(
            summary = "Subir archivo de imagen al producto",
            description = """
                    Carga un archivo de imagen (PNG, JPG, WebP) directamente al almacenamiento del servidor y lo vincula al producto.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.products.update")
    public ProductMediaResponse upload(
            @PathVariable UUID productId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String altText,
            @RequestParam(required = false) Integer sortOrder,
            @RequestParam(required = false) Boolean primary) {
        return service.upload(productId, file, altText, sortOrder, primary);
    }

    @Operation(
            summary = "Actualizar metadatos de un medio multimedia",
            description = """
                    Modifica el texto alternativo (alt), orden numérico de visualización o estado de imagen principal.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping("/{mediaId}")
    @RequirePermission("catalog.products.update")
    public ProductMediaResponse update(
            @PathVariable UUID productId,
            @PathVariable UUID mediaId,
            @Valid @RequestBody ProductMediaUpdateRequest request) {
        return service.update(productId, mediaId, request);
    }

    @Operation(
            summary = "Eliminar medio multimedia de un producto",
            description = """
                    Elimina la imagen de la galería del producto y remueve su archivo asociado del almacenamiento.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.products.update")
    public void delete(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        service.delete(productId, mediaId);
    }

    @Operation(
            summary = "Marcar medio como imagen principal del producto",
            description = """
                    Establece el medio indicado como la imagen de portada / thumbnail principal del producto en la tienda y POS.
                    
                    **Permisos requeridos:**
                    * `catalog.products.update`
                    """
    )
    @PutMapping("/{mediaId}/primary")
    @RequirePermission("catalog.products.update")
    public ProductMediaResponse primary(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        return service.setPrimary(productId, mediaId);
    }
}
