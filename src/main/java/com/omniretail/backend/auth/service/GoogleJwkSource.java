package com.omniretail.backend.auth.service;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.MalformedURLException;
import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * Llaves publicas con que Google firma sus ID token. Se descargan al primer uso y se cachean (y se
 * refrescan cuando Google las rota). Los tests reemplazan este bean por llaves propias.
 */
@Component
public class GoogleJwkSource {

    static final String JWK_SET_URL = "https://www.googleapis.com/oauth2/v3/certs";

    private final JWKSource<SecurityContext> source;

    public GoogleJwkSource() {
        this(remote());
    }

    public GoogleJwkSource(JWKSource<SecurityContext> source) {
        this.source = source;
    }

    JWKSource<SecurityContext> source() {
        return source;
    }

    private static JWKSource<SecurityContext> remote() {
        try {
            return JWKSourceBuilder.create(URI.create(JWK_SET_URL).toURL()).build();
        } catch (MalformedURLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
