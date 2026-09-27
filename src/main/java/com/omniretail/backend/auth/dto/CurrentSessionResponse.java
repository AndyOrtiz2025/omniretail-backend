package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Respuesta de {@code GET /auth/me}. Nunca incluye el hash de la contrasena ni datos de auth_accounts.
 * Los campos llevan los mismos nombres que las entidades del frontend (User.ts, Role.ts, Tenant.ts,
 * Session.ts); solo se agregan campos, nunca se quitan ni renombran.
 */
public record CurrentSessionResponse(UserView user, RoleView role, TenantView tenant, SessionView session) {

    /** Mismos campos que User.ts del frontend. {@code allowedBranchIds} nunca es null. */
    public record UserView(
            UUID id, String name, String email, String phone, UserType type, UserStatus status,
            UUID tenantId, UUID customerId, String employeeCode, UUID roleId, UUID branchId,
            List<UUID> allowedBranchIds, Instant createdAt, Instant updatedAt) {

        public static UserView from(User user) {
            List<UUID> allowedBranchIds = user.getAllowedBranchIds() != null
                    ? List.copyOf(user.getAllowedBranchIds())
                    : List.of();
            return new UserView(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getType(),
                    user.getStatus(), user.getTenantId(), user.getCustomerId(), user.getEmployeeCode(),
                    user.getRoleId(), user.getBranchId(), allowedBranchIds, user.getCreatedAt(), user.getUpdatedAt());
        }
    }

    /** {@code isSystem} nunca es null. */
    public record RoleView(
            UUID id, String name, List<String> permissions, BranchScope branchScope, RoleStatus status,
            UUID tenantId, String description, @JsonProperty("isSystem") boolean isSystem,
            Instant createdAt, Instant updatedAt) {

        public static RoleView from(Role role) {
            List<String> permissions = role.getPermissions() != null ? List.copyOf(role.getPermissions()) : List.of();
            return new RoleView(role.getId(), role.getName(), permissions, role.getBranchScope(), role.getStatus(),
                    role.getTenantId(), role.getDescription(), Boolean.TRUE.equals(role.getIsSystem()),
                    role.getCreatedAt(), role.getUpdatedAt());
        }
    }

    public record TenantView(
            UUID id, String name, String slug, String legalName, TenantStatus status, String defaultCurrency,
            String timezone, Instant createdAt, Instant updatedAt) {

        public static TenantView from(Tenant tenant) {
            return new TenantView(tenant.getId(), tenant.getName(), tenant.getSlug(), tenant.getLegalName(),
                    tenant.getStatus(), tenant.getDefaultCurrency(), tenant.getTimezone(), tenant.getCreatedAt(),
                    tenant.getUpdatedAt());
        }
    }

    public record SessionView(
            UUID id, Instant expiresAt, Boolean rememberMe, UUID activeBranchId, Instant createdAt, String deviceLabel) {

        public static SessionView from(Session session) {
            return new SessionView(session.getId(), session.getExpiresAt(), session.getRememberMe(),
                    session.getActiveBranchId(), session.getCreatedAt(), session.getDeviceLabel());
        }
    }
}
