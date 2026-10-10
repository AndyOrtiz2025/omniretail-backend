package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.ChangeActiveBranchRequest;
import com.omniretail.backend.auth.dto.ChangePasswordRequest;
import com.omniretail.backend.auth.dto.CurrentSessionResponse;
import com.omniretail.backend.auth.dto.LoginOutcome;
import com.omniretail.backend.auth.dto.LoginRequest;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.dto.MfaChallengeResponse;
import com.omniretail.backend.auth.dto.SessionBranchResponse;
import com.omniretail.backend.auth.dto.SessionEntitlementsResponse;
import com.omniretail.backend.auth.service.ActiveBranchService;
import com.omniretail.backend.auth.service.AuthService;
import com.omniretail.backend.auth.service.CurrentSessionService;
import com.omniretail.backend.auth.service.PasswordChangeService;
import com.omniretail.backend.auth.service.SessionEntitlementsService;
import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /login} es publico; {@code /logout}, {@code /me}, {@code /password/change}, {@code /session/branch}, {@code /session/branches} y {@code /session/entitlements} exigen token (ver SecurityConfig). */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Inicio y cierre de sesión, sesión actual y sucursal activa.")
public class AuthController {

    private final AuthService authService;
    private final CurrentSessionService currentSessionService;
    private final PasswordChangeService passwordChangeService;
    private final ActiveBranchService activeBranchService;
    private final SessionEntitlementsService sessionEntitlementsService;
    private final CurrentUser currentUser;

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Log in",
            description = """
                    **Público.** Inicia sesión de un empleado o de un cliente con correo y contraseña y devuelve el
                    token JWT de la sesión.

                    - Empleados: sesión de 8 horas; `rememberMe` se ignora.
                    - Clientes: requieren el `tenantSlug` de la tienda. La sesión dura 2 horas, o 30 días con `rememberMe`.
                    - Bloqueo escalonado: el 5.º fallo en 10 minutos (contraseña o código MFA) bloquea la cuenta
                      15, 30 o 60 minutos, según los bloqueos de las últimas 24 horas.
                    - Si la cuenta tiene MFA activo, no entrega sesión: devuelve un challenge
                      `{ mfaRequired: true, challengeToken, method, expiresAt }` sin token, y la sesión se obtiene en
                      `POST /auth/mfa/verify`. Con `method: "email"` además envía el código por correo.

                    El mensaje de error es siempre genérico: no revela si el correo existe ni si la cuenta está bloqueada.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sesión iniciada (`LoginResponse`), o desafío de segundo factor si el usuario tiene MFA "
                        + "activo (`MfaChallengeResponse`).",
                content = @Content(mediaType = "application/json", schema = @Schema(
                        oneOf = {LoginResponse.class, MfaChallengeResponse.class}))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos (`VALIDATION_ERROR`) o cuerpo de la solicitud ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No fue posible iniciar sesión (`INVALID_CREDENTIALS`). Mismo mensaje para correo "
                        + "inexistente, contraseña incorrecta o cuenta bloqueada o inactiva.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public LoginOutcome login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Log out",
            description = "**Requiere sesión (cliente o empleado).** Revoca la sesión del token actual. Desde ese "
                    + "momento el token deja de ser válido en cualquier endpoint.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Sesión cerrada."),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión ya revocada. Responde sin cuerpo.")
    })
    public void logout() {
        authService.logout(currentUser.require().sessionId());
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Change password",
            description = """
                    **Requiere sesión (cliente o empleado).** Cambia la contraseña de la cuenta de la sesión actual.

                    - Exige la contraseña actual; la nueva debe ser distinta y cumplir la política del tipo de cuenta
                      (clientes: 8 a 24 caracteres; empleados: 12 a 24; con mayúscula, minúscula, número y carácter especial).
                    - Con la verificación en dos pasos activa, `mfaCode` es obligatorio: código de la app, código
                      por correo (se pide en `POST /auth/mfa/code`) o código de recuperación.
                    - Al cambiarla se revocan todas las demás sesiones del usuario; la sesión actual sigue activa.""")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Contraseña cambiada."),
        @ApiResponse(
                responseCode = "400",
                description = "`VALIDATION_ERROR` con `fields.currentPassword` (la contraseña actual no es correcta), "
                        + "`fields.newPassword` (igual a la actual o no cumple la política) o `fields.mfaCode` "
                        + "(falta o no es válido con MFA activo); o cuerpo ilegible "
                        + "(`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión revocada (sin cuerpo); o la cuenta ya no "
                        + "existe (`UNAUTHENTICATED`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public void changePassword(@RequestBody ChangePasswordRequest request) {
        passwordChangeService.change(currentUser.require(), request);
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get current session",
            description = "**Requiere sesión (cliente o empleado).** Reconstruye la sesión del token: datos del usuario "
                    + "(incluidas sus sucursales permitidas), tienda, rol con sus permisos y datos de la sesión. Para "
                    + "clientes sin rol activo, `role` es `null`.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sesión actual.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = CurrentSessionResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión revocada (sin cuerpo); o usuario "
                        + "inactivo, tienda inactiva o empleado sin rol activo (`UNAUTHENTICATED`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public CurrentSessionResponse me() {
        return currentSessionService.resolve(currentUser.require());
    }

    @PatchMapping("/session/branch")
    @Operation(
            summary = "Change active branch",
            description = """
                    **Solo empleados.** Guarda la sucursal elegida en el selector del encabezado, solo para la sesión
                    del token.

                    - La sucursal debe estar activa, ser de la tienda de la sesión y estar entre las sucursales
                      asignadas al usuario (`allowedBranchIds`, o `branchId` si nunca se asignaron). Un rol con
                      `branchScope = all` no amplía esta lista.
                    - Responde lo mismo que `GET /auth/me`, ya con la sucursal nueva en `session.activeBranchId`.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sucursal activa actualizada; sesión actual.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = CurrentSessionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Campos no permitidos (`VALIDATION_ERROR`, con `fields`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión revocada. Responde sin cuerpo."),
        @ApiResponse(
                responseCode = "403",
                description = "`BRANCH_NOT_ALLOWED`: la sesión no es de un empleado, o la sucursal no existe, no está "
                        + "activa o no está permitida. Mismo mensaje en todos los casos.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public CurrentSessionResponse changeActiveBranch(@RequestBody ChangeActiveBranchRequest request) {
        return activeBranchService.change(currentUser.require(), request);
    }

    @GetMapping("/session/branches")
    @Operation(
            summary = "List session branches",
            description = """
                    **Solo empleados.** Devuelve las sucursales que el empleado puede elegir en el selector de la
                    sesión. No exige `admin.branches.read`: es la lectura operativa para roles sin permiso
                    administrativo de sucursales.

                    - Solo sucursales activas de la tienda de la sesión que estén entre las asignadas al usuario
                      (`allowedBranchIds`, o `branchId` si nunca se asignaron). Un rol con `branchScope = all` no
                      amplía esta lista; es la misma regla de `PATCH /auth/session/branch`.
                    - Sin sucursales asignadas (`allowedBranchIds` vacío) responde `[]`.
                    - Ordenadas por nombre y luego por código. No incluye dirección, teléfono ni correo.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sucursales de la sesión.",
                content = @Content(
                        mediaType = "application/json",
                        array = @ArraySchema(schema = @Schema(implementation = SessionBranchResponse.class)))),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión revocada. Responde sin cuerpo."),
        @ApiResponse(
                responseCode = "403",
                description = "`BRANCH_NOT_ALLOWED`: la sesión no es de un empleado activo de la tienda.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public List<SessionBranchResponse> listSessionBranches() {
        return activeBranchService.listSessionBranches(currentUser.require());
    }

    @GetMapping("/session/entitlements")
    @Operation(
            summary = "Session entitlements",
            description = """
                    **Solo empleados.** Devuelve las capacidades y límites del plan del negocio de la sesión, para que
                    la interfaz sepa qué módulos (POS, inventario, recepción...) puede ofrecer. No exige
                    `admin.plans.read`: es la lectura operativa para roles sin permiso administrativo de planes.

                    - `capabilities` incluye las del plan y las de sus complementos; `effectiveCapabilities` solo las
                      trae si la suscripción y el plan están activos (misma regla con la que el backend valida cada
                      operación).
                    - No incluye facturas, precios, complementos ni otros planes.
                    - Sin suscripción responde 404 `TENANT_SUBSCRIPTION_NOT_FOUND`.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Entitlements del negocio de la sesión.",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = SessionEntitlementsResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión revocada. Responde sin cuerpo."),
        @ApiResponse(
                responseCode = "403",
                description = "`ENTITLEMENTS_NOT_ALLOWED`: la sesión no es de un empleado activo de la tienda.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = "`TENANT_SUBSCRIPTION_NOT_FOUND` o `SAAS_PLAN_NOT_FOUND`.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public SessionEntitlementsResponse sessionEntitlements() {
        return sessionEntitlementsService.getSessionEntitlements(currentUser.require());
    }
}
