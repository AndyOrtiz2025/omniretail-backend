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
        name = "Configuración del negocio",
        description = "Configuración global y operativa del tenant (nombre comercial, moneda, régimen fiscal, zona horaria y parámetros generales del sistema)."
)
@RestController
@RequestMapping("/administration/business-config")
@RequiredArgsConstructor
public class BusinessConfigController {

    private final BusinessConfigService businessConfigService;

    @Operation(
            summary = "Obtener configuración general del negocio",
            description = """
                    Retorna la configuración operativa y fiscal vigente del tenant actual.
                    
                    **Notas de uso:**
                    * Este endpoint no restringe por permisos específicos de administración ya que es consumido transversalmente por módulos operativos (POS, Facturación, Inventario, Catálogo y Tienda en línea) para resolver moneda, impuestos por defecto y datos emisores.
                    * Requiere sesión activa del usuario autenticado en el tenant.
                    """
    )
    // Sin permiso: POS, inventario, recepcion y catalogo leen esta configuracion (igual que el frontend).
    @GetMapping
    public BusinessConfigResponse get() {
        return businessConfigService.getConfig();
    }

    @Operation(
            summary = "Actualizar configuración general del negocio",
            description = """
                    Actualiza los parámetros globales del negocio (razón social, RFC/NIT, dirección fiscal, moneda base, tasa general de IVA, etc.).
                    
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
