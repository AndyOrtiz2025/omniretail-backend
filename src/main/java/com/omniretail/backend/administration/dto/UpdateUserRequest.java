package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code email}, {@code type}, {@code customerId} y {@code tenantId} deliberadamente NO forman
 * parte de este DTO (auditoría §13/§25, ver {@code UpdateEmployeeService.ts}): un usuario nunca se
 * convierte de empleado a cliente (ni viceversa), no se le cambia el email acá (evita
 * desincronización con {@code AuthAccount}), ni se muda de tenant.
 *
 * <p>{@code status} no acepta {@code archived} acá (ver {@code EDITABLE_STATUSES} en {@code
 * employee.validation.ts}): un empleado no se archiva editando su estado, eso queda fuera del
 * ciclo de vida cubierto por este endpoint.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 120) String name,
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
        @NotNull UserStatus status) {
}
