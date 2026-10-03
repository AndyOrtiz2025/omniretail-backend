package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.ChangeActiveBranchRequest;
import com.omniretail.backend.auth.dto.ChangePasswordRequest;
import com.omniretail.backend.auth.dto.CurrentSessionResponse;
import com.omniretail.backend.auth.dto.LoginRequest;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.service.ActiveBranchService;
import com.omniretail.backend.auth.service.AuthService;
import com.omniretail.backend.auth.service.CurrentSessionService;
import com.omniretail.backend.auth.service.PasswordChangeService;
import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /login} es publico; {@code /logout}, {@code /me}, {@code /password/change} y {@code /session/branch} exigen token (ver SecurityConfig). */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticación", description = "Inicio y cierre de sesión, y sesión actual.")
public class AuthController {

    private final AuthService authService;
    private final CurrentSessionService currentSessionService;
    private final PasswordChangeService passwordChangeService;
    private final ActiveBranchService activeBranchService;
    private final CurrentUser currentUser;

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Iniciar sesión",
            description = """
                    Inicia sesión de un empleado o de un cliente y devuelve el token JWT de la sesión.

                    - Empleados: sesión de 8 horas; `rememberMe` se ignora.
                    - Clientes: requieren el `tenantSlug` de la tienda. La sesión dura 2 horas, o 30 días con `rememberMe`.
                    - Tras 5 intentos fallidos la cuenta se bloquea 15 minutos.

                    El mensaje de error es siempre genérico: no revela si el correo existe ni si la cuenta está bloqueada.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sesión iniciada.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginResponse.class))),
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
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Cerrar sesión",
            description = "Revoca la sesión del token actual. Desde ese momento el token deja de ser válido en "
                    + "cualquier endpoint.")
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
            summary = "Cambiar contraseña",
            description = """
                    Cambia la contraseña de la cuenta de la sesión actual (cliente o empleado).

                    - Exige la contraseña actual; la nueva debe ser distinta y cumplir la política del tipo de cuenta
                      (clientes: 8 a 24 caracteres; empleados: 12 a 24; con mayúscula, minúscula, número y carácter especial).
                    - Al cambiarla se revocan todas las demás sesiones del usuario; la sesión actual sigue activa.""")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Contraseña cambiada."),
        @ApiResponse(
                responseCode = "400",
                description = "`VALIDATION_ERROR` con `fields.currentPassword` (la contraseña actual no es correcta) o "
                        + "`fields.newPassword` (igual a la actual o no cumple la política); o cuerpo ilegible "
                        + "(`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sin token, token inválido o vencido, o sesión revocada. Responde sin cuerpo.")
    })
    public void changePassword(@RequestBody ChangePasswordRequest request) {
        passwordChangeService.change(currentUser.require(), request);
    }

    @GetMapping("/me")
    @Operation(
            summary = "Obtener la sesión actual",
            description = "Reconstruye la sesión del token: datos del usuario (incluidas sus sucursales permitidas), "
                    + "tienda, rol con sus permisos y datos de la sesión. Para clientes sin rol activo, `role` es `null`.")
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
            summary = "Cambiar la sucursal activa de la sesión",
            description = """
                    Guarda la sucursal elegida en el selector del encabezado, solo para la sesión del token.

                    - Solo empleados.
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
}
