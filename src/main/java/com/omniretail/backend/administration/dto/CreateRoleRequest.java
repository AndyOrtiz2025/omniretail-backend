package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.RoleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code branchScope} deliberadamente NO forma parte de este DTO (ver {@code RoleDto.ts}): lo
 * define el {@code User} (rol + sucursales asignadas), no el Rol. Un rol nuevo siempre nace con
 * {@code BranchScope.assigned}.
 */
public record CreateRoleRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 240) String description,
        List<String> permissions,
        RoleStatus status) {
}
