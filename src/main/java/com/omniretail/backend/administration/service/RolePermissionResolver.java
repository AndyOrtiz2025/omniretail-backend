package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.shared.security.PermissionResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lee los permisos del rol en la BD en cada request, para que un cambio de permisos aplique de inmediato. */
@Service
@RequiredArgsConstructor
public class RolePermissionResolver implements PermissionResolver {

    private final RoleRepository roleRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID tenantId, UUID roleId, String permission) {
        return roleRepository.findById(roleId)
                .filter(role -> role.getTenantId().equals(tenantId))
                .filter(role -> role.getStatus() == RoleStatus.active)
                .map(role -> role.getPermissions().contains(permission))
                .orElse(false);
    }
}
