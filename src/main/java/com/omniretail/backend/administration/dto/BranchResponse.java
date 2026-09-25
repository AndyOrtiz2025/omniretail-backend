package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import java.time.Instant;
import java.util.UUID;

public record BranchResponse(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        BranchType type,
        String address,
        String phone,
        String email,
        BranchStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static BranchResponse from(Branch branch) {
        return new BranchResponse(
                branch.getId(),
                branch.getTenantId(),
                branch.getCode(),
                branch.getName(),
                branch.getType(),
                branch.getAddress(),
                branch.getPhone(),
                branch.getEmail(),
                branch.getStatus(),
                branch.getCreatedAt(),
                branch.getUpdatedAt());
    }
}
