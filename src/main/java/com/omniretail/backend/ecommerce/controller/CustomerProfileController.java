package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.CustomerProfileResponse;
import com.omniretail.backend.ecommerce.dto.UpdateCustomerProfileRequest;
import com.omniretail.backend.ecommerce.service.CustomerProfileService;
import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Autoservicio del cliente: el tenant y el cliente se resuelven desde el JWT. */
@RestController
@RequestMapping("/me/profile")
@RequiredArgsConstructor
@Tag(name = "Mi cuenta", description = "Perfil, direcciones y métodos de pago del cliente autenticado.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Sin token, token inválido o vencido, o sesión revocada."),
    @ApiResponse(
            responseCode = "403",
            description = "Sin el permiso (`ACCESS_DENIED`), o la sesión no es de un cliente activo "
                    + "(`CUSTOMER_ACCOUNT_REQUIRED`).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
})
public class CustomerProfileController {

    private final CustomerProfileService customerProfileService;

    @GetMapping
    @RequirePermission("customer.account.read")
    @Operation(summary = "Consultar mi perfil", description = "Devuelve los datos personales del cliente autenticado.")
    @ApiResponse(
            responseCode = "200",
            description = "Perfil del cliente.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CustomerProfileResponse.class)))
    public CustomerProfileResponse get() {
        return customerProfileService.get();
    }

    @PutMapping
    @RequirePermission("customer.account.update")
    @Operation(
            summary = "Actualizar mi perfil",
            description = """
                    Actualiza nombre y teléfono del cliente autenticado, también en su usuario para que `/auth/me` lo refleje.

                    - `name`: obligatorio, máximo 100 caracteres.
                    - `phone`: opcional, exactamente 8 dígitos; ausente o vacío lo elimina.
                    - El correo no se cambia aquí. Cualquier otro campo se rechaza con `VALIDATION_ERROR`.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Perfil actualizado.",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = CustomerProfileResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos o campos no permitidos (`VALIDATION_ERROR`, con `fields`), o cuerpo "
                        + "ilegible (`REQUEST_ERROR`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public CustomerProfileResponse update(@RequestBody UpdateCustomerProfileRequest request) {
        return customerProfileService.update(request);
    }
}
