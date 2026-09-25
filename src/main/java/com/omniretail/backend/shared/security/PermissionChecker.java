package com.omniretail.backend.shared.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Bean que evalua {@link RequirePermission} desde su expresion {@code @PreAuthorize}. */
@Component("permissionChecker")
@RequiredArgsConstructor
public class PermissionChecker {

    private final PermissionResolver permissionResolver;

    /**
     * false (403) si no hay usuario autenticado, no tiene rol o el rol no concede el permiso. Sin
     * token la request ya se corta antes con 401 en SecurityConfig.
     */
    public boolean hasPermission(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return false;
        }
        return user.roleId() != null
                && permissionResolver.hasPermission(user.tenantId(), user.roleId(), permission);
    }
}
