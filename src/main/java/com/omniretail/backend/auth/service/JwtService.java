package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.shared.security.JwtClaimNames;
import com.omniretail.backend.shared.security.JwtProperties;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Emite el JWT de una sesion. El token solo lleva identificadores (usuario, tenant, rol, sucursal,
 * sesion); nunca email, contrasena ni permisos. Vence junto con la sesion.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateToken(User user, Session session) {
        if (!user.getId().equals(session.getUserId())) {
            throw new IllegalArgumentException("La sesion no pertenece al usuario.");
        }
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuer(jwtProperties.issuer())
                .issuedAt(Instant.now())
                .expiresAt(session.getExpiresAt())
                .claim(JwtClaimNames.TENANT_ID, user.getTenantId().toString())
                .claim(JwtClaimNames.USER_TYPE, user.getType().name())
                .claim(JwtClaimNames.SESSION_ID, session.getId().toString());
        if (user.getRoleId() != null) {
            claims.claim(JwtClaimNames.ROLE_ID, user.getRoleId().toString());
        }
        if (user.getBranchId() != null) {
            claims.claim(JwtClaimNames.BRANCH_ID, user.getBranchId().toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
