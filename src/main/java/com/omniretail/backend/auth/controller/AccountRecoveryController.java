package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.ForgotPasswordRequest;
import com.omniretail.backend.auth.dto.ForgotPasswordResponse;
import com.omniretail.backend.auth.dto.RegisterCustomerResponse;
import com.omniretail.backend.auth.dto.ResendEmailVerificationRequest;
import com.omniretail.backend.auth.dto.ResendEmailVerificationResponse;
import com.omniretail.backend.auth.dto.ResetPasswordRequest;
import com.omniretail.backend.auth.dto.ResetPasswordResponse;
import com.omniretail.backend.auth.dto.VerifyEmailRequest;
import com.omniretail.backend.auth.dto.VerifyEmailResponse;
import com.omniretail.backend.auth.service.CustomerRegistrationService;
import com.omniretail.backend.auth.service.PasswordRecoveryService;
import com.omniretail.backend.shared.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints publicos (ver PUBLIC_PATHS en SecurityConfig). */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Account recovery", description = "Verificación de correo y recuperación de contraseña. Endpoints públicos.")
public class AccountRecoveryController {

    static final String GENERIC_RECOVERY_MESSAGE = "Si existe una cuenta asociada, recibirás instrucciones.";

    private final CustomerRegistrationService registrationService;
    private final PasswordRecoveryService passwordRecoveryService;

    @PostMapping("/email/verify")
    @SecurityRequirements
    @Operation(
            summary = "Verify email",
            description = "**Público.** Consume el token del enlace de verificación y activa la cuenta si estaba pendiente de "
                    + "verificación. Una cuenta deshabilitada o archivada no se reactiva. Devuelve el `tenantSlug` "
                    + "de la tienda para armar la URL de inicio de sesión.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Correo verificado.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = VerifyEmailResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Token inexistente, ya usado, reemplazado por un enlace más nuevo o vencido "
                        + "(`INVALID_OR_EXPIRED_TOKEN`, sin distinguir el motivo), o cuerpo inválido (`VALIDATION_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public VerifyEmailResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return registrationService.verifyEmail(request.token());
    }

    @PostMapping("/email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(
            summary = "Resend email verification",
            description = """
                    **Público, solo clientes.** Envía un enlace de verificación nuevo (vence en 30 minutos) a la cuenta \
                    de cliente pendiente de verificación con ese correo en la tienda del `tenantSlug`.

                    - El enlace anterior deja de servir.
                    - Máximo 3 enlaces por cuenta cada 30 minutos, contando el del registro; los demás se omiten.
                    - Cuenta inexistente o ya verificada, empleado, tienda inexistente o inactiva: no se envía nada.

                    Siempre responde lo mismo: no revela si la cuenta existe, si ya está verificada, si se alcanzó \
                    el límite ni si hubo un error interno.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Solicitud aceptada. Mismo cuerpo en todos los casos.",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = ResendEmailVerificationResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Correo o tienda faltantes o inválidos (`VALIDATION_ERROR`, con `fields`), o cuerpo "
                        + "ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResendEmailVerificationResponse resendVerification(
            @Valid @RequestBody ResendEmailVerificationRequest request) {
        try {
            registrationService.resendVerification(request.email(), request.tenantSlug());
        } catch (RuntimeException ex) {
            // Nunca se delata un error interno: se registra (sin el correo) y se responde igual que siempre.
            log.error("Error al procesar un reenvio de verificacion de correo", ex);
        }
        return new ResendEmailVerificationResponse(RegisterCustomerResponse.ACCEPTED_MESSAGE);
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(
            summary = "Request password reset",
            description = """
                    **Público.** Envía un enlace de recuperación (vence en 15 minutos) a cada cuenta elegible con ese correo: \
                    clientes de la tienda del `tenantSlug` (sin él, ninguno) y empleados de cualquier tienda.

                    - Solo cuentas activas o bloqueadas temporalmente.
                    - Máximo 3 solicitudes por cuenta cada 30 minutos; las demás se omiten.
                    - Una solicitud nueva invalida el enlace anterior.

                    Siempre responde lo mismo: no revela si la cuenta existe, si se alcanzó el límite ni si hubo \
                    un error interno.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Solicitud recibida (respuesta idéntica en todos los casos).",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = ForgotPasswordResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Correo vacío o con formato inválido (`VALIDATION_ERROR`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            passwordRecoveryService.requestReset(request.email(), request.tenantSlug());
        } catch (RuntimeException ex) {
            // Nunca se delata un error interno: se registra y se responde igual que siempre.
            log.error("Error al procesar una solicitud de recuperacion de contrasena", ex);
        }
        return new ForgotPasswordResponse(GENERIC_RECOVERY_MESSAGE);
    }

    @PostMapping("/password/reset")
    @SecurityRequirements
    @Operation(
            summary = "Reset password",
            description = """
                    **Público.** Cambia la contraseña con el token del enlace de recuperación. La política depende del tipo de \
                    cuenta (clientes: 8 a 64 caracteres; empleados: 12 a 64; en ambos casos como máximo 72 bytes en UTF-8).

                    Cierra todas las sesiones abiertas de la cuenta y desbloquea una cuenta bloqueada \
                    temporalmente. `tenantSlug` solo viene para clientes.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Contraseña restablecida.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = ResetPasswordResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Token inexistente, usado, reemplazado o vencido, o cuenta no elegible "
                        + "(`INVALID_OR_EXPIRED_TOKEN`); o contraseña que no cumple la política (`VALIDATION_ERROR` "
                        + "con `fields.newPassword`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResetPasswordResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return passwordRecoveryService.resetPassword(request.token(), request.newPassword());
    }
}
