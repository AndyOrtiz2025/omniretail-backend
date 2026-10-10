package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.EmailSenderResponse;
import com.omniretail.backend.administration.dto.SaveEmailSenderRequest;
import com.omniretail.backend.administration.dto.TestEmailSenderRequest;
import com.omniretail.backend.administration.service.EmailSenderConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Remitente Gmail del tenant. El tenant sale siempre del JWT; nunca de la ruta ni del body. */
@Tag(
        name = "Configuración de Servidor de Correo",
        description = "Gestión de credenciales de envío SMTP (Gmail) personalizadas por tenant para notificaciones operativas de pedidos y órdenes de compra."
)
@RestController
@RequestMapping("/administration/email-sender")
@RequiredArgsConstructor
public class EmailSenderConfigController {

    private final EmailSenderConfigService emailSenderConfigService;

    @Operation(
            summary = "Obtener configuración de remitente de correo",
            description = """
                    Recupera el estado de conexión y los parámetros del remitente configurado para el tenant (correo emisor, estado de verificación, fecha de última prueba).
                    
                    **Permisos requeridos:**
                    * `admin.email_config.read` o `admin.email_config.manage`
                    """
    )
    @PreAuthorize("@permissionChecker.hasPermission('admin.email_config.read')"
            + " or @permissionChecker.hasPermission('admin.email_config.manage')")
    @GetMapping
    public EmailSenderResponse get() {
        return emailSenderConfigService.getConfig();
    }

    @Operation(
            summary = "Guardar configuración de correo saliente",
            description = """
                    Configura las credenciales de correo (Gmail con Contraseña de Aplicación) para el envío automatizado de correos electrónicos transaccionales del tenant.
                    
                    **Permisos requeridos:**
                    * `admin.email_config.manage`
                    """
    )
    @RequirePermission("admin.email_config.manage")
    @PutMapping
    public EmailSenderResponse save(@RequestBody SaveEmailSenderRequest request) {
        return emailSenderConfigService.saveConfig(request);
    }

    @Operation(
            summary = "Enviar correo de prueba",
            description = """
                    Envía un mensaje de prueba a un destinatario objetivo para verificar la validez de las credenciales SMTP configuradas y la conectividad con el servidor de correo.
                    
                    **Permisos requeridos:**
                    * `admin.email_config.manage`
                    """
    )
    @RequirePermission("admin.email_config.manage")
    @PostMapping("/test")
    public EmailSenderResponse test(@RequestBody TestEmailSenderRequest request) {
        return emailSenderConfigService.sendTest(request);
    }

    @Operation(
            summary = "Desconectar y eliminar configuración de correo",
            description = """
                    Elimina las credenciales de envío de correo del tenant, desactivando el remitente personalizado y retornando al comportamiento predeterminado del sistema.
                    
                    **Permisos requeridos:**
                    * `admin.email_config.manage`
                    """
    )
    @RequirePermission("admin.email_config.manage")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect() {
        emailSenderConfigService.disconnect();
    }
}
