package com.omniretail.backend.shared.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@code Authentication} cuyo principal es el {@link AuthenticatedUser}. Sin authorities: los
 * permisos no viajan en el token, se resuelven en los services (capas 2-4).
 */
public class AuthenticatedUserToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;
    private final Jwt jwt;

    public AuthenticatedUserToken(AuthenticatedUser principal, Jwt jwt) {
        super(List.of());
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }
}
