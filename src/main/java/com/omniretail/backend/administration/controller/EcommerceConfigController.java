package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.EcommerceConfigResponse;
import com.omniretail.backend.administration.dto.SaveEcommerceConfigRequest;
import com.omniretail.backend.administration.service.EcommerceConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(
        name = "Configuración de E-commerce",
        description = "Administración de parámetros de la tienda en línea (branding, costos de envío, métodos de pago, mensajes y logotipo institucional)."
)
@RestController
@RequestMapping("/administration/ecommerce-config")
@RequiredArgsConstructor
public class EcommerceConfigController {

    private final EcommerceConfigService ecommerceConfigService;

    @Operation(
            summary = "Obtener configuración de tienda en línea",
            description = """
                    Recupera los parámetros completos de personalización y operación del storefront web del tenant.
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @GetMapping
    public EcommerceConfigResponse get() {
        return ecommerceConfigService.getConfig();
    }

    @Operation(
            summary = "Actualizar configuración de tienda en línea",
            description = """
                    Actualiza la configuración visual y operativa del canal de comercio electrónico (colores de marca, montos mínimos de compra, tarifas de envío, información de contacto y soporte).
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @PutMapping
    public EcommerceConfigResponse save(@Valid @RequestBody SaveEcommerceConfigRequest request) {
        return ecommerceConfigService.saveConfig(request);
    }

    @Operation(
            summary = "Subir logotipo del storefront",
            description = """
                    Carga un archivo de imagen (PNG, JPG, SVG, WebP) para ser utilizado como logotipo oficial en la cabecera y comprobantes del storefront.
                    
                    **Restricciones:**
                    * Formato multipart/form-data con la parte llamada `file`.
                    * Límite de tamaño configurado por el sistema de almacenamiento.
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EcommerceConfigResponse uploadLogo(@RequestPart("file") MultipartFile file) {
        return ecommerceConfigService.uploadLogo(file);
    }

    @Operation(
            summary = "Eliminar logotipo del storefront",
            description = """
                    Elimina el logotipo personalizado previamente subido para el tenant, restableciendo la visualización al isotipo predeterminado de la plataforma.
                    
                    **Permisos requeridos:**
                    * `admin.ecommerce_config.manage`
                    """
    )
    @RequirePermission("admin.ecommerce_config.manage")
    @DeleteMapping("/logo")
    public EcommerceConfigResponse deleteLogo() {
        return ecommerceConfigService.deleteLogo();
    }
}
