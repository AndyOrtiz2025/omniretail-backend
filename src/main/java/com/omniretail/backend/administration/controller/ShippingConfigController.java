package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.SaveShippingConfigRequest;
import com.omniretail.backend.administration.dto.ShippingConfigResponse;
import com.omniretail.backend.administration.service.ShippingConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Shipping configuration",
        description = "Administración del costo de envío a domicilio por canal (tienda en línea y POS): tarifa fija y umbral de envío gratis."
)
@RestController
@RequestMapping("/administration/shipping-config")
@RequiredArgsConstructor
public class ShippingConfigController {

    private final ShippingConfigService shippingConfigService;

    @Operation(
            summary = "Get shipping configuration",
            description = """
                    Recupera la tarifa de envío a domicilio de cada canal (`ecommerce`, `pos`): `enabled`, `deliveryFee` y `freeShippingThreshold`.
                    Si un canal no se ha configurado se devuelven sus valores por defecto (ecommerce: Q25, gratis desde Q300; pos: sin cobro).

                    **Permisos requeridos:**
                    * `admin.shipping_config.manage`
                    """
    )
    @RequirePermission("admin.shipping_config.manage")
    @GetMapping
    public ShippingConfigResponse get() {
        return shippingConfigService.getConfig();
    }

    @Operation(
            summary = "Update shipping configuration",
            description = """
                    Actualiza la tarifa de envío a domicilio de ambos canales definida en `SaveShippingConfigRequest`:
                    * `enabled`: Si el canal cobra envío; `false` registra envío Q0.
                    * `deliveryFee`: Tarifa fija (>= 0, dos decimales).
                    * `freeShippingThreshold`: Subtotal desde el cual el envío es gratis (>= 0); `null` = nunca gratis.

                    **Permisos requeridos:**
                    * `admin.shipping_config.manage`
                    """
    )
    @RequirePermission("admin.shipping_config.manage")
    @PutMapping
    public ShippingConfigResponse save(@Valid @RequestBody SaveShippingConfigRequest request) {
        return shippingConfigService.saveConfig(request);
    }
}
