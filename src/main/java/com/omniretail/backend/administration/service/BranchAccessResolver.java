package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Alcance de sucursales del usuario autenticado. Mismo algoritmo que {@code canUserAccessBranch}
 * (userBranchAccess.ts del frontend):
 *
 * <ul>
 *   <li>{@code Role.branchScope == all}: cualquier sucursal del tenant.
 *   <li>{@code assigned} o {@code selected}: solo {@code User.allowedBranchIds}. Si nunca se
 *       asignó (null), se usa {@code User.branchId} como respaldo; una lista vacía significa
 *       "ninguna sucursal" a propósito y no cae al respaldo.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BranchAccessResolver {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public BranchAccess resolve(AuthenticatedUser actor) {
        UUID tenantId = actor.tenantId();
        boolean allBranches = actor.roleId() != null
                && roleRepository
                        .findByTenantIdAndId(tenantId, actor.roleId())
                        .map(Role::getBranchScope)
                        .filter(scope -> scope == BranchScope.all)
                        .isPresent();
        if (allBranches) {
            return BranchAccess.ALL;
        }
        return userRepository
                .findByTenantIdAndId(tenantId, actor.userId())
                .map(BranchAccessResolver::allowedBranchIds)
                .map(ids -> new BranchAccess(false, Set.copyOf(ids)))
                .orElse(BranchAccess.NONE);
    }

    /** Sucursales asignadas al usuario, sin el bypass de {@code branchScope == all} (resolveUserAllowedBranchIds). */
    public static List<UUID> allowedBranchIds(User user) {
        if (user.getAllowedBranchIds() != null) {
            return user.getAllowedBranchIds();
        }
        return user.getBranchId() != null ? List.of(user.getBranchId()) : List.of();
    }

    public record BranchAccess(boolean allBranches, Set<UUID> branchIds) {

        static final BranchAccess ALL = new BranchAccess(true, Set.of());
        static final BranchAccess NONE = new BranchAccess(false, Set.of());

        public boolean allows(UUID branchId) {
            return allBranches || branchIds.contains(branchId);
        }
    }
}
