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
@Tag(name = "Customer registration", description = "Registro público de clientes en una tienda.")
public class CustomerRegistrationController {

    private final CustomerRegistrationService registrationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(
            summary = "Register customer",
            description = """
                    **Público.** Registra a un cliente en la tienda del `slug`. Responde **siempre lo mismo** (202), \
                    exista o no el correo en la tienda: nunca revela si ya hay una cuenta.

                    - Correo nuevo: crea la cuenta pendiente de verificación y envía el enlace (vence en 30 minutos). \
                    No puede iniciar sesión hasta abrirlo.
                    - Correo de un cliente con la cuenta ya verificada: no cambia nada y le envía el aviso "Ya tienes \
                    una cuenta" (como máximo 3 cada 30 minutos).
                    - Correo de un empleado o de una cuenta deshabilitada o archivada: no hace nada.
                    - `phone` es opcional; si viene, debe tener exactamente 8 dígitos.
                    - `password`: 8 a 24 caracteres, con mayúscula, minúscula, número y carácter especial; sin \
                    espacios, no solo números, distinta del correo y no común.

                    La respuesta no incluye el id de la cuenta: `user` solo repite el nombre y el correo enviados \
                    (normalizados). Nunca incluye el token de verificación.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Solicitud aceptada. Mismo cuerpo exista o no el correo.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = RegisterCustomerResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos (`VALIDATION_ERROR`, con `fields`) o cuerpo ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = "La tienda no existe o no está activa (`REGISTRATION_UNAVAILABLE`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public RegisterCustomerResponse register(@PathVariable String slug, @RequestBody RegisterCustomerRequest request) {
        try {
            return registrationService.register(slug, request);
        } catch (CustomerRegistrationService.ConcurrentRegistrationException ex) {
            // Otro registro con el mismo correo gano la carrera: la transaccion ya se deshizo; misma respuesta.
            return ex.response();
        }
    }
}
