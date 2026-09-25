package com.omniretail.backend.shared.security;

import com.omniretail.backend.administration.entity.UserType;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Convierte un JWT ya validado (firma, {@code iss}, {@code exp}) en un {@link AuthenticatedUserToken}.
 *
 * <p>Si falta o es invalido {@code sub}, {@code tenantId}, {@code sid} o {@code userType} lanza
 * {@link InvalidBearerTokenException}: el resource server la responde como 401, igual que un token
 * vencido o mal firmado. El claim que fallo no se revela al cliente.
 */
@Component
public class TenantJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String INVALID_TOKEN = "Token invalido.";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthenticatedUser user = new AuthenticatedUser(
                requiredUuid(jwt.getSubject()),
                requiredUuid(jwt.getClaimAsString(JwtClaimNames.TENANT_ID)),
                requiredUserType(jwt.getClaimAsString(JwtClaimNames.USER_TYPE)),
                optionalUuid(jwt.getClaimAsString(JwtClaimNames.ROLE_ID)),
                optionalUuid(jwt.getClaimAsString(JwtClaimNames.BRANCH_ID)),
                requiredUuid(jwt.getClaimAsString(JwtClaimNames.SESSION_ID)));
        return new AuthenticatedUserToken(user, jwt);
    }

    private static UUID requiredUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidBearerTokenException(INVALID_TOKEN);
        }
        return parseUuid(value);
    }

    private static UUID optionalUuid(String value) {
        return value == null || value.isBlank() ? null : parseUuid(value);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException(INVALID_TOKEN, ex);
        }
    }

    private static UserType requiredUserType(String value) {
        if (value == null) {
            throw new InvalidBearerTokenException(INVALID_TOKEN);
        }
        try {
            return UserType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException(INVALID_TOKEN, ex);
        }
    }
}
