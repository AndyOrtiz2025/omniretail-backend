package com.omniretail.backend.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifica un ID token de Google Identity Services: firma RS256 con las llaves publicas de Google, emisor
 * de Google, {@code aud} igual a GOOGLE_CLIENT_ID, vigencia, y correo confirmado por Google
 * ({@code email_verified}). El decoder vive aqui y no como bean: el {@code JwtDecoder} de la app es el de
 * nuestros propios tokens.
 *
 * <p>El ID token nunca se guarda ni se escribe en logs.
 */
@Slf4j
@Component
public class GoogleIdTokenVerifier {

    static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    /** Datos de Google que se usan; nada mas (ni foto ni otros claims). */
    public record GoogleIdentity(String subject, String email, String name) {
    }

    private final GoogleAuthProperties properties;
    private final NimbusJwtDecoder decoder;

    public GoogleIdTokenVerifier(GoogleAuthProperties properties, GoogleJwkSource jwkSource, Clock authClock) {
        this.properties = properties;
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource.source()));
        // Las reglas de los claims las aplican los validadores de abajo (con el reloj de auth).
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        this.decoder = new NimbusJwtDecoder(processor);
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(authClock);
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, this::validateClaims));
    }

    public boolean isConfigured() {
        return properties.configured();
    }

    /** Vacio si el token no es valido por cualquier motivo; el motivo no se distingue hacia afuera. */
    public Optional<GoogleIdentity> verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException ex) {
            log.debug("ID token de Google rechazado: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
        String name = jwt.getClaimAsString("name");
        return Optional.of(new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), name));
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        boolean valid = jwt.getExpiresAt() != null
                && ISSUERS.contains(jwt.getClaimAsString("iss"))
                && jwt.getAudience() != null && jwt.getAudience().contains(properties.clientId())
                && notBlank(jwt.getSubject())
                && notBlank(jwt.getClaimAsString("email"))
                && emailVerified(jwt.getClaims().get("email_verified"));
        return valid
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    /** Google lo manda como booleano; algunos tokens antiguos, como texto. */
    private static boolean emailVerified(Object claim) {
        return Boolean.TRUE.equals(claim) || "true".equals(claim);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
