package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * R-A11: nunca incluye password, tokens ni {@code isSystem} -- el administrador no define
 * contraseñas acá. Tampoco incluye {@code tenantId} ni {@code customerId}: el tenant sale siempre
 * del JWT del actor, y este endpoint no crea clientes.
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 200) String email,
        @Size(max = 20) String phone,
        @Size(max = 50) String employeeCode,
        @NotNull UserType type,
        UUID roleId,
        UUID branchId,
        List<UUID> allowedBranchIds,
        UserStatus status) {
}
