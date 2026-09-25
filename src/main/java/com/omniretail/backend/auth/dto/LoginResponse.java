package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import java.time.Instant;
import java.util.UUID;

public record LoginResponse(String token, Instant expiresAt, UserSummary user) {

    /** Datos publicos del usuario autenticado. Nunca incluye el hash de la contrasena. */
    public record UserSummary(
            UUID id, String name, String email, UserType type, UUID tenantId, UUID roleId, UUID branchId) {

        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getName(), user.getEmail(), user.getType(),
                    user.getTenantId(), user.getRoleId(), user.getBranchId());
        }
    }
}
