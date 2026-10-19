package com.omniretail.backend.commercial;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tope del cuerpo de {@code POST /api/v1/public/contact-requests} antes de que Jackson lo lea: 413 si
 * {@code Content-Length} supera el maximo configurado y 411 si no se declara (cuerpos "chunked"). El endpoint es
 * anonimo, asi que no se acepta un cuerpo de tamano desconocido.
 */
@Component
@ContactRequestsEnabled
class ContactRequestBodyLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/public/contact-requests";

    private final ContactRequestProperties properties;

    ContactRequestBodyLimitFilter(ContactRequestProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            reject(request, response, 411, "Length Required", "REQUEST_LENGTH_REQUIRED",
                    "La solicitud debe indicar su tamaño.");
            return;
        }
        if (length > properties.maxBodyBytes()) {
            reject(request, response, 413, "Payload Too Large", "REQUEST_TOO_LARGE",
                    "La solicitud es demasiado grande.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(
            HttpServletRequest request, HttpServletResponse response, int status, String error, String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + error + "\",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\",\"path\":\"" + PATH + "\",\"timestamp\":\""
                + Instant.now() + "\"}");
    }
}
