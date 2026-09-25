package com.omniretail.backend.shared.security;

import com.omniretail.backend.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Acceso al usuario autenticado de la request actual. Unica fuente valida del {@code tenantId}. */
@Component
public class CurrentUser {

    /**
     * Devuelve el usuario autenticado o responde 401. Lanza {@link BusinessException} y no una
     * {@code AuthenticationException} porque el {@code GlobalExceptionHandler} convertiria esta
     * ultima en 500.
     */
    public AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Debes iniciar sesion.");
    }
}
