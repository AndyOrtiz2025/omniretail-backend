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
        name = "E-commerce configuration",
        description = "Administración de parámetros del storefront en línea (activación, datos de tienda, contacto, checkout/seguimiento de invitados, métodos de entrega y pago permitidos, sucursal por defecto y logotipo)."
)
@RestController
@RequestMapping("/administration/ecommerce-config")
@RequiredArgsConstructor
public class EcommerceConfigController {

    private final EcommerceConfigService ecommerceConfigService;

    @Operation(
            summary = "Get e-commerce configuration",
            description = """
                    Recupera los parámetros de configuración del canal de comercio electrónico del tenant (`enabled`, `storeName`, `logoUrl`, `contactPhone`, `contactEmail`, `requireAccountForCheckout`, `guestTrackingEnabled`, `allowedDeliveryMethods`, `allowedPaymentMethods`, `defaultBranchId`).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
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
            summary = "Update e-commerce configuration",
            description = """
                    Actualiza la configuración operativa del canal de comercio electrónico definida en `SaveEcommerceConfigRequest`:
                    * `enabled`: Estado de habilitación de la tienda en línea.
                    * `storeName`, `logoUrl`, `contactPhone`, `contactEmail`: Datos de identificación y contacto de la tienda.
                    * `requireAccountForCheckout`, `guestTrackingEnabled`: Políticas de compra con cuenta y rastreo de pedidos de invitados.
                    * `allowedDeliveryMethods`: Métodos de entrega habilitados (`store_pickup`, `home_delivery`).
                    * `allowedPaymentMethods`: Métodos de pago habilitados (`cash`, `card`, `transfer`).
                    * `defaultBranchId`: Sucursal activa por defecto para la operación de e-commerce.
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
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
            summary = "Upload storefront logo",
            description = """
                    Carga un archivo de imagen (JPEG, PNG o WebP, máximo 5 MB) para ser utilizado como logotipo (`logoUrl`) en la tienda en línea.
                    
                    **Restricciones:**
                    * Formato `multipart/form-data` con la parte llamada `file`.
                    * Tipos permitidos: `image/jpeg`, `image/png`, `image/webp` (máximo 5 MB).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
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
            summary = "Delete storefront logo",
            description = """
                    Elimina el logotipo personalizado previamente subido para el storefront del tenant (`logoUrl`).
                    
                    **Capacidad SaaS requerida:**
                    * `ecommerce`
                    
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
