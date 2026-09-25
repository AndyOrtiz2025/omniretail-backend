package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        Boolean isSystem,
        List<String> permissions,
        BranchScope branchScope,
        RoleStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getTenantId(),
                role.getName(),
                role.getDescription(),
                role.getIsSystem(),
                role.getPermissions(),
                role.getBranchScope(),
                role.getStatus(),
                role.getCreatedAt(),
                role.getUpdatedAt());
    }
}
