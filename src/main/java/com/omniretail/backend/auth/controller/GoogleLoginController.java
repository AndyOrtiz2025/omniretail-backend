package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.GoogleLoginRequest;
import com.omniretail.backend.auth.dto.LoginOutcome;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.dto.MfaChallengeResponse;
import com.omniretail.backend.auth.service.GoogleLoginService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /google} es publico (ver SecurityConfig). */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticación", description = "Inicio y cierre de sesión, y sesión actual.")
public class GoogleLoginController {

    private final GoogleLoginService googleLoginService;

    @PostMapping("/google")
    @SecurityRequirements
    @Operation(
            summary = "Iniciar sesión con Google",
            description = """
                    Inicia sesión de un cliente de la tienda con el ID token (`credential`) de Google Identity Services.

                    - Solo clientes: requiere el `tenantSlug`; `expectedUserType`, si viene, debe ser `customer`.
                    - Si el correo no existe en la tienda, crea la cuenta de cliente (activa, sin contraseña: para
                      tener una, "Olvidé mi contraseña"). Si existe, la vincula a la cuenta de Google.
                    - Responde igual que `POST /auth/login`: la sesión, o el desafío de MFA si el cliente lo tiene activo.
                    - Error siempre genérico: token inválido o vencido, correo no confirmado por Google, cuenta de
                      empleado, bloqueada o inactiva, o ya vinculada a otra cuenta de Google.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Sesión iniciada (`LoginResponse`), o desafío de segundo factor (`MfaChallengeResponse`).",
                content = @Content(mediaType = "application/json", schema = @Schema(
                        oneOf = {LoginResponse.class, MfaChallengeResponse.class}))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos (`VALIDATION_ERROR`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No fue posible iniciar sesión (`INVALID_CREDENTIALS`). Mismo mensaje que el login.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "503",
                description = "`GOOGLE_LOGIN_NOT_CONFIGURED`: el backend no tiene GOOGLE_CLIENT_ID.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public LoginOutcome login(@Valid @RequestBody GoogleLoginRequest request) {
        return googleLoginService.login(request);
    }
}
