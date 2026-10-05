package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.BeginMfaEnrollmentRequest;
import com.omniretail.backend.auth.dto.BeginMfaEnrollmentResponse;
import com.omniretail.backend.auth.dto.DisableMfaRequest;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.dto.MfaRecoveryCodesResponse;
import com.omniretail.backend.auth.dto.MfaStatusResponse;
import com.omniretail.backend.auth.dto.ResendMfaCodeRequest;
import com.omniretail.backend.auth.dto.VerifyMfaChallengeRequest;
import com.omniretail.backend.auth.dto.VerifyMfaEnrollmentRequest;
import com.omniretail.backend.auth.service.MfaLoginService;
import com.omniretail.backend.auth.service.MfaService;
import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verificacion en dos pasos (TOTP) de clientes y empleados. {@code /verify} es publico (segundo paso del
 * login); el resto exige token (ver SecurityConfig).
 */
@RestController
@RequestMapping("/auth/mfa")
@RequiredArgsConstructor
@Tag(name = "Autenticación", description = "Inicio y cierre de sesión, y sesión actual.")
public class MfaController {

    private static final String UNAUTHORIZED_DESCRIPTION =
            "Sin token, token inválido o vencido, o sesión revocada. Responde sin cuerpo.";

    private final MfaService mfaService;
    private final MfaLoginService mfaLoginService;
    private final CurrentUser currentUser;

    @PostMapping("/verify")
    @SecurityRequirements
    @Operation(
            summary = "Completar el inicio de sesión con el segundo factor",
            description = """
                    Segundo paso del login cuando la cuenta tiene MFA activo. Recibe el `challengeToken` del login y
                    el código de 6 dígitos de la app, o un código de recuperación `XXXX-XXXX` (cada uno sirve una vez).

                    - El desafío vence en 5 minutos, es de un solo uso y admite 5 intentos.
                    - Cada código incorrecto suma al bloqueo de la cuenta, igual que una contraseña incorrecta.
                    - Responde lo mismo que `POST /auth/login` sin MFA.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sesión iniciada.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Campos no permitidos (`VALIDATION_ERROR`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "`MFA_CODE_INVALID`: código incorrecto, se puede reintentar con el mismo desafío. "
                        + "`MFA_CHALLENGE_UNAVAILABLE`: desafío vencido, usado, agotado, reemplazado por otro login, "
                        + "o cuenta bloqueada; hay que volver a iniciar sesión.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public LoginResponse verifyChallenge(@RequestBody VerifyMfaChallengeRequest request) {
        return mfaLoginService.verify(request);
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    @Operation(
            summary = "Reenviar el código por correo del inicio de sesión",
            description = """
                    Solo para el método por correo. Envía un código nuevo y el anterior deja de servir. El nuevo
                    vence a los 5 minutos, sin pasar los 15 minutos de vida del desafío. Conserva los intentos
                    fallidos del desafío.

                    Límites: 60 s entre correos, 3 reenvíos por desafío y 10 correos por usuario por hora.""")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Código reenviado."),
        @ApiResponse(
                responseCode = "400",
                description = "`MFA_RESEND_NOT_AVAILABLE` (el método es la app), campos no permitidos "
                        + "(`VALIDATION_ERROR`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "`MFA_CHALLENGE_UNAVAILABLE`: desafío vencido, usado, reemplazado o cuenta bloqueada.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "429",
                description = "`MFA_CODE_RESEND_LIMITED`: todavía no se puede pedir otro código.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public void resendChallengeCode(@RequestBody ResendMfaCodeRequest request) {
        mfaLoginService.resend(request);
    }

    @PostMapping("/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Enviar un código por correo para el cambio de contraseña",
            description = """
                    Solo con el MFA por correo activo. El código vence a los 5 minutos y se usa como `mfaCode` en
                    `POST /auth/password/change`. Pedir otro invalida el anterior. Mismos límites de envío.""")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Código enviado."),
        @ApiResponse(
                responseCode = "400",
                description = "`MFA_EMAIL_CODE_NOT_AVAILABLE`: el MFA no está activo o su método es la app.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION),
        @ApiResponse(
                responseCode = "429",
                description = "`MFA_CODE_RESEND_LIMITED`.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public void sendActionCode() {
        mfaService.sendActionCode(currentUser.require());
    }

    @GetMapping
    @Operation(
            summary = "Consultar mi verificación en dos pasos",
            description = "`enabled` indica si está activa. `method` es `null` si nunca se inició una activación.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Estado del MFA.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = MfaStatusResponse.class))),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION)
    })
    public MfaStatusResponse status() {
        return mfaService.status(currentUser.require());
    }

    @PostMapping("/enrollment")
    @Operation(
            summary = "Iniciar la activación",
            description = """
                    La activación queda pendiente hasta confirmar un código en `POST /auth/mfa/enrollment/verify`.

                    - `method: "totp"`: genera un secreto nuevo y devuelve el `otpauthUri` (para el QR) y el
                      secreto en Base32.
                    - `method: "email"`: envía un código de 6 dígitos al correo de la cuenta (vence en 5 min).
                      `secret` y `otpauthUri` vienen en `null`.
                    - Repetirla antes de confirmar reemplaza el secreto o el código pendiente. Con correo cuenta
                      como un envío: 60 s entre envíos y 10 correos por hora (429 `MFA_CODE_RESEND_LIMITED`).
                    - Con el MFA ya activo responde 409: primero hay que desactivarlo (pide la contraseña).""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Activación pendiente.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = BeginMfaEnrollmentResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "`VALIDATION_ERROR` (`fields.method` o campos no permitidos) o cuerpo ilegible "
                        + "(`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION),
        @ApiResponse(
                responseCode = "409",
                description = "`MFA_ALREADY_ENABLED`.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "429",
                description = "`MFA_CODE_RESEND_LIMITED` (solo correo).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public BeginMfaEnrollmentResponse beginEnrollment(@RequestBody BeginMfaEnrollmentRequest request) {
        return mfaService.beginEnrollment(currentUser.require(), request);
    }

    @PostMapping("/enrollment/verify")
    @Operation(
            summary = "Confirmar la activación",
            description = """
                    Activa el MFA con un código de la app y devuelve los 8 códigos de recuperación. Se muestran una
                    sola vez: no hay forma de volver a consultarlos.

                    Tras 5 códigos incorrectos se descarta el secreto pendiente y hay que iniciar la activación de nuevo.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "MFA activo.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = MfaRecoveryCodesResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "`MFA_CODE_INVALID`, `MFA_ENROLLMENT_RESET` (demasiados intentos), campos no "
                        + "permitidos (`VALIDATION_ERROR`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION),
        @ApiResponse(
                responseCode = "409",
                description = "`MFA_ENROLLMENT_NOT_PENDING`: no hay una activación pendiente.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public MfaRecoveryCodesResponse verifyEnrollment(@RequestBody VerifyMfaEnrollmentRequest request) {
        return mfaService.verifyEnrollment(currentUser.require(), request);
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Desactivar",
            description = "Pide la contraseña actual. Borra el secreto y los códigos de recuperación. Si no estaba "
                    + "activo, no cambia nada.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "MFA desactivado."),
        @ApiResponse(
                responseCode = "400",
                description = "`VALIDATION_ERROR` con `fields.currentPassword` (la contraseña actual no es correcta) "
                        + "o campos no permitidos; o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION)
    })
    public void disable(@RequestBody DisableMfaRequest request) {
        mfaService.disable(currentUser.require(), request);
    }
}
