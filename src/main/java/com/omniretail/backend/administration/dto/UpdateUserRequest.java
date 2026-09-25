package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code email}, {@code type}, {@code customerId} y {@code tenantId} deliberadamente NO forman
 * parte de este DTO (auditoría §13/§25, ver {@code UserRepository.updateScoped} en el frontend):
 * un usuario nunca se convierte de empleado a cliente (ni viceversa), no se le cambia el email acá
 * (evita desincronización con {@code AuthAccount}), ni se muda de tenant.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 20) String phone,
        @Size(max = 50) String employeeCode,
        UUID roleId,
        UUID branchId,
        List<UUID> allowedBranchIds,
        @NotNull UserStatus status) {
}
