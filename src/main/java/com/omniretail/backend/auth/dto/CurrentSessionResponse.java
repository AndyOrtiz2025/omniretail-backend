package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Respuesta de {@code GET /auth/me}. Nunca incluye el hash de la contrasena ni datos de auth_accounts. */
public record CurrentSessionResponse(UserView user, RoleView role, TenantView tenant, SessionView session) {

    public record UserView(
            UUID id, String name, String email, UserType type, UserStatus status,
            UUID tenantId, UUID roleId, UUID branchId) {

        public static UserView from(User user) {
            return new UserView(user.getId(), user.getName(), user.getEmail(), user.getType(), user.getStatus(),
                    user.getTenantId(), user.getRoleId(), user.getBranchId());
        }
    }

    public record RoleView(UUID id, String name, List<String> permissions, BranchScope branchScope, RoleStatus status) {

        public static RoleView from(Role role) {
            return new RoleView(role.getId(), role.getName(), List.copyOf(role.getPermissions()),
                    role.getBranchScope(), role.getStatus());
        }
    }

    public record TenantView(UUID id, String name, String slug) {

        public static TenantView from(Tenant tenant) {
            return new TenantView(tenant.getId(), tenant.getName(), tenant.getSlug());
        }
    }

    public record SessionView(UUID id, Instant expiresAt, Boolean rememberMe, UUID activeBranchId) {

        public static SessionView from(Session session) {
            return new SessionView(session.getId(), session.getExpiresAt(), session.getRememberMe(),
                    session.getActiveBranchId());
        }
    }
}
