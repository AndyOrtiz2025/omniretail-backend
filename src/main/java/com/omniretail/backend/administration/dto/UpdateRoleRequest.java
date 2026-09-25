package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.RoleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code branchScope} deliberadamente NO forma parte de este DTO (ver {@code RoleDto.ts}): editar
 * un rol nunca toca su {@code branchScope}, eso es responsabilidad de {@code User}.
 *
 * <p>{@code permissions} es {@code @NotNull} (no {@code @NotEmpty} a proposito, se permite un rol
 * sin permisos) para que un update sin este campo falle la validacion en vez de vaciar en
 * silencio los permisos existentes del rol.
 */
public record UpdateRoleRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 240) String description,
        @NotNull List<String> permissions,
        RoleStatus status) {
}
