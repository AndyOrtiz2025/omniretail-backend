package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.dto.SaveBusinessConfigRequest;
import com.omniretail.backend.administration.service.BusinessConfigService;
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
        name = "Business configuration",
        description = "Configuración operativa del tenant (preset de giro comercial, capacidades operativas de inventario/catálogo, seguimiento por defecto y métodos de pago permitidos en POS)."
)
@RestController
@RequestMapping("/administration/business-config")
@RequiredArgsConstructor
public class BusinessConfigController {

    private final BusinessConfigService businessConfigService;

    @Operation(
            summary = "Get business configuration",
            description = """
                    Retorna la configuración operativa vigente del tenant actual (`preset`, banderas de capacidades de inventario y catálogo, `allowedPosPaymentMethods` y `defaultProductTracking`).
                    
                    **Notas de uso:**
                    * Este endpoint no exige un permiso específico de administración porque es consultado transversalmente por módulos operativos (POS, inventario, recepción y catálogo).
                    * Requiere sesión activa de un empleado del tenant.
                    """
    )
    // Sin permiso: POS, inventario, recepcion y catalogo leen esta configuracion (igual que el frontend).
    @GetMapping
    public BusinessConfigResponse get() {
        return businessConfigService.getConfig();
    }

    @Operation(
            summary = "Update business configuration",
            description = """
                    Actualiza la configuración operativa del negocio:
                    * `preset`: Giro comercial (`hardware_store`, `pharmacy`, `grocery`, `services`, `custom`).
                    * Capacidades operativas: `supportsInventory`, `supportsLots`, `supportsExpiration`, `supportsSerials`, `supportsMultipleLocations`, `supportsUnitsAndPackaging`, `supportsProductAttributes`, `supportsKits`, `supportsServices`.
                    * `allowedPosPaymentMethods`: Métodos de pago habilitados en POS (`cash`, `card`, `transfer`, `mixed`).
                    * `defaultProductTracking`: Configuración predeterminada de trazabilidad (`trackInventory`, `trackingLots`, `trackingExpiration`, `trackingSerial`).
                    
                    **Permisos requeridos:**
                    * `admin.business_config.manage`
                    """
    )
    @RequirePermission("admin.business_config.manage")
    @PutMapping
    public BusinessConfigResponse save(@Valid @RequestBody SaveBusinessConfigRequest request) {
        return businessConfigService.saveConfig(request);
    }
}
