package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.ActivateEmployeeRequest;
import com.omniretail.backend.auth.dto.ActivateEmployeeResponse;
import com.omniretail.backend.auth.service.EmployeeInvitationService;
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

/** Activacion publica de empleados; no comparte el flujo de registro de clientes. */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Activación de empleados", description = "Activación de cuentas mediante invitación.")
public class EmployeeActivationController {

    private final EmployeeInvitationService invitationService;

    @PostMapping("/activate-employee")
    @SecurityRequirements
    @Operation(summary = "Activar cuenta de empleado",
            description = "Consume una invitación vigente, establece la contraseña y revoca las sesiones anteriores. "
                    + "La contraseña debe cumplir la política de empleados (12 a 24 caracteres).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cuenta activada correctamente.",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ActivateEmployeeResponse.class))),
        @ApiResponse(responseCode = "400",
                description = "Enlace inválido, usado, reemplazado, vencido o cuenta no elegible "
                        + "(`INVALID_OR_EXPIRED_TOKEN`, sin distinguir el motivo); o datos inválidos "
                        + "(`VALIDATION_ERROR`, con `fields.newPassword` para la política de contraseña).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ActivateEmployeeResponse activateEmployee(@Valid @RequestBody ActivateEmployeeRequest request) {
        return invitationService.activateEmployeeAccount(request.token(), request.newPassword());
    }
}
