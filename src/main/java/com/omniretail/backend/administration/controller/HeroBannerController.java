package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.HeroBannerConfigResponse;
import com.omniretail.backend.administration.dto.SaveHeroBannerConfigRequest;
import com.omniretail.backend.administration.service.HeroBannerConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(
        name = "Hero banners",
        description = "Administración de diapositivas (slides), imágenes y carrusel principal mostrado en la portada de la tienda en línea."
)
@RestController
@RequestMapping("/administration/hero-banner")
@RequiredArgsConstructor
public class HeroBannerController {

    private final HeroBannerConfigService heroBannerConfigService;

    @Operation(
            summary = "Get hero banner configuration",
            description = """
                    Recupera las 3 diapositivas (`slides`, índices `0` a `2`) configuradas en la portada de la tienda (`title`, `description`, `imageUrl`).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @GetMapping
    public HeroBannerConfigResponse get() {
        return heroBannerConfigService.getBanner();
    }

    @Operation(
            summary = "Update hero banner configuration",
            description = """
                    Guarda la configuración de exactamente 3 diapositivas (`slides`) del carrusel principal (`title`, `description` e `imageUrl` opcional).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @PutMapping
    public HeroBannerConfigResponse save(@Valid @RequestBody SaveHeroBannerConfigRequest request) {
        return heroBannerConfigService.saveBanner(request);
    }

    @Operation(
            summary = "Upload hero banner slide image",
            description = """
                    Carga un archivo de imagen (JPEG, PNG o WebP, máximo 5 MB) y lo asigna al slide ubicado en el índice especificado dentro del carrusel.
                    
                    **Parámetros:**
                    * `index`: Índice basado en cero del slide a actualizar (`0`, `1` o `2`).
                    * `file`: Archivo de imagen `multipart/form-data` (`image/jpeg`, `image/png` o `image/webp`, máx. 5 MB).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @PostMapping(value = "/slides/{index}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HeroBannerConfigResponse uploadSlideImage(
            @PathVariable int index, @RequestPart("file") MultipartFile file) {
        return heroBannerConfigService.uploadSlideImage(index, file);
    }

    @Operation(
            summary = "Delete hero banner slide image",
            description = """
                    Remueve la imagen asociada a la diapositiva en el índice provisto (`0`, `1` o `2`), dejando `imageUrl` en `null`.
                    
                    **Parámetros:**
                    * `index`: Índice basado en cero del slide (`0`, `1` o `2`).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @DeleteMapping("/slides/{index}/image")
    public HeroBannerConfigResponse deleteSlideImage(@PathVariable int index) {
        return heroBannerConfigService.deleteSlideImage(index);
    }
}
