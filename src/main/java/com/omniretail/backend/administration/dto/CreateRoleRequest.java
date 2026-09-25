package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.RoleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateRoleRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 240) String description,
        List<String> permissions,
        BranchScope branchScope,
        RoleStatus status) {
}
