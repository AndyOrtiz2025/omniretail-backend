package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Solo crea EMPLEADOS (ver {@code CreateEmployeeService.ts}): deliberadamente no tiene {@code
 * type} -- el servicio siempre asigna {@code UserType.employee}, este endpoint nunca crea
 * clientes. R-A11: tampoco incluye password, tokens ni {@code isSystem} (el administrador no
 * define contraseñas acá) ni {@code tenantId}/{@code customerId} (el tenant sale del JWT del
 * actor).
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 200) String email,
        @Pattern(
                        regexp = "^$|^[0-9]{4}-[0-9]{4}$|^[0-9]{8}$",
                        message = "Formato de telefono invalido (debe ser 0000-0000 u 8 digitos)")
                @Size(max = 9)
                String phone,
        @NotBlank
                @Size(max = 20)
                @Pattern(
                        regexp = "^[A-Za-z0-9_-]+$",
                        message = "El código de empleado solo puede contener letras, números, guión y guión bajo.")
                String employeeCode,
        @NotNull UUID roleId,
        UUID branchId,
        List<UUID> allowedBranchIds,
        UserStatus status) {
}
