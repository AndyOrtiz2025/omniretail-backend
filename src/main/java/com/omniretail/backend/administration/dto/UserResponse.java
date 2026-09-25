package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID tenantId,
        UUID customerId,
        String employeeCode,
        String name,
        String email,
        String phone,
        UserType type,
        UserStatus status,
        UUID roleId,
        UUID branchId,
        List<UUID> allowedBranchIds,
        Instant createdAt,
        Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getTenantId(),
                user.getCustomerId(),
                user.getEmployeeCode(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getType(),
                user.getStatus(),
                user.getRoleId(),
                user.getBranchId(),
                user.getAllowedBranchIds() != null ? user.getAllowedBranchIds() : List.of(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
