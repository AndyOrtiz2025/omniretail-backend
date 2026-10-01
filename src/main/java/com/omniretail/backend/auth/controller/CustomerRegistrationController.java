package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.RegisterCustomerRequest;
import com.omniretail.backend.auth.dto.RegisterCustomerResponse;
import com.omniretail.backend.auth.service.CustomerRegistrationService;
import com.omniretail.backend.shared.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Publico (entra por {@code /api/v1/public/**}, ver SecurityConfig). */
@RestController
@RequestMapping("/public/{slug}/auth")
@RequiredArgsConstructor
@Tag(name = "Cuenta del cliente", description = "Registro, verificación de correo y recuperación de contraseña.")
public class CustomerRegistrationController {

    private final CustomerRegistrationService registrationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(
            summary = "Registrar un cliente",
            description = """
                    Crea la cuenta de un cliente en la tienda del `slug`. La cuenta queda pendiente de verificación: \
                    no puede iniciar sesión hasta abrir el enlace que se envía por correo (vence en 30 minutos).

                    - `phone` es opcional; si viene, debe tener exactamente 8 dígitos.
                    - `password`: 8 a 24 caracteres, con mayúscula, minúscula, número y carácter especial; sin \
                    espacios, no solo números, distinta del correo y no común.

                    La respuesta nunca incluye el token de verificación.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Cliente registrado; se envía el correo de verificación.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = RegisterCustomerResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos (`VALIDATION_ERROR`, con `fields`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = "La tienda no existe o no está activa (`REGISTRATION_UNAVAILABLE`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Ya existe una cuenta con ese correo en la tienda (`EMAIL_ALREADY_REGISTERED`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public RegisterCustomerResponse register(@PathVariable String slug, @RequestBody RegisterCustomerRequest request) {
        return registrationService.register(slug, request);
    }
}
