package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.CurrentSessionResponse;
import com.omniretail.backend.auth.dto.CurrentSessionResponse.RoleView;
import com.omniretail.backend.auth.dto.CurrentSessionResponse.SessionView;
import com.omniretail.backend.auth.dto.CurrentSessionResponse.TenantView;
import com.omniretail.backend.auth.dto.CurrentSessionResponse.UserView;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstruye la sesion actual a partir del token (equivale a resolveCurrentSessionSnapshot del
 * frontend). La vigencia de la sesion ya la valido el converter del JWT antes de llegar aqui.
 */
@Service
@RequiredArgsConstructor
public class CurrentSessionService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final SessionRepository sessionRepository;

    @Transactional(readOnly = true)
    public CurrentSessionResponse resolve(AuthenticatedUser principal) {
        User user = userRepository.findById(principal.userId())
                .filter(found -> found.getTenantId().equals(principal.tenantId()))
                .filter(found -> found.getStatus() == UserStatus.active)
                .orElseThrow(CurrentSessionService::unauthenticated);
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(CurrentSessionService::unauthenticated);
        if (user.getType() == UserType.employee && tenant.getStatus() != TenantStatus.active) {
            throw unauthenticated();
        }
        // Employee sin rol activo no puede operar: 401. Customer sin rol es normal: role = null.
        RoleView role = activeRole(user);
        if (user.getType() == UserType.employee && role == null) {
            throw unauthenticated();
        }
        Session session = sessionRepository.findById(principal.sessionId())
                .orElseThrow(CurrentSessionService::unauthenticated);

        return new CurrentSessionResponse(UserView.from(user), role, TenantView.from(tenant),
                SessionView.from(session));
    }

    /** Solo si existe, es del mismo tenant y esta activo; si no, null. */
    private RoleView activeRole(User user) {
        if (user.getRoleId() == null) {
            return null;
        }
        return roleRepository.findById(user.getRoleId())
                .filter(role -> role.getTenantId().equals(user.getTenantId()))
                .filter(role -> role.getStatus() == RoleStatus.active)
                .map(RoleView::from)
                .orElse(null);
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Debes iniciar sesion.");
    }
}
