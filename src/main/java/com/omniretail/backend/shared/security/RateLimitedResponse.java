package com.omniretail.backend.shared.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Cuerpo unico del 429 {@code RATE_LIMITED}. Lo usan el filtro por IP y el limite de fallos por IP + email:
 * el frontend ve siempre los mismos campos, y {@code retryAfterSeconds} coincide con la cabecera
 * {@code Retry-After}.
 */
public final class RateLimitedResponse {

    public static final String CODE = "RATE_LIMITED";
    public static final String MESSAGE = "Demasiados intentos. Intenta de nuevo en unos momentos.";

    private RateLimitedResponse() {
    }

    public static Map<String, Object> body(String path, long retryAfterSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("code", CODE);
        body.put("message", MESSAGE);
        body.put("path", path);
        body.put("timestamp", Instant.now().toString());
        body.put("retryAfterSeconds", retryAfterSeconds);
        return body;
    }
}
