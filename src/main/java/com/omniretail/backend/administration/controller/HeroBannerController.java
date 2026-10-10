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
        name = "Banners promocionales (Hero Banner)",
        description = "Administración de diapositivas (slides), imágenes y carrusel principal mostrado en la portada de la tienda en línea."
)
@RestController
@RequestMapping("/administration/hero-banner")
@RequiredArgsConstructor
public class HeroBannerController {

    private final HeroBannerConfigService heroBannerConfigService;

    @Operation(
            summary = "Obtener configuración de banners principales",
            description = """
                    Recupera la lista de slides promocionales, textos, botones CTA e imágenes configuradas en la portada de la tienda.
                    
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
            summary = "Actualizar configuración de banners principales",
            description = """
                    Guarda la estructura, contenido textual, enlaces de destino y orden de visualización de los slides del carrusel.
                    
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
            summary = "Subir imagen para un slide específico",
            description = """
                    Carga un archivo de imagen y lo asigna al slide ubicado en el índice especificado dentro del carrusel.
                    
                    **Parámetros:**
                    * `index`: Índice numérico basado en cero del slide a actualizar.
                    * `file`: Archivo multimedia multipart/form-data.
                    
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
            summary = "Eliminar imagen de un slide específico",
            description = """
                    Remueve la imagen asociada a la diapositiva en el índice provisto, dejando el slide sin banner gráfico.
                    
                    **Parámetros:**
                    * `index`: Índice numérico basado en cero del slide.
                    
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
