package com.omniretail.backend.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limita las rutas anonimas con mayor potencial de abuso antes de llegar a los controllers. La clave
 * combina la IP del cliente y el grupo de ruta; asi un limite de recuperacion no bloquea el checkout.
 *
 * <p>En produccion el backend no publica puertos y Caddy reemplaza {@code X-Forwarded-For} con la IP
 * remota. Si alguien alcanza el backend sin ese proxy, se usa la direccion del socket y no un header
 * controlado por el cliente.
 */
final class PublicRateLimitFilter extends OncePerRequestFilter {

    private enum RouteGroup {
        LOGIN, MFA, RECOVERY, REGISTRATION, CHECKOUT, TRACKING, CONTACT
    }

    private record Window(long startedAtMillis, int requests) {
    }

    private record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final PublicRateLimitProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * Un mapa por grupo: el tope de clientes rastreados es por grupo, asi una avalancha contra un endpoint
     * (p. ej. el formulario comercial) no agota el cupo de login o recuperacion.
     */
    private final Map<RouteGroup, Map<String, Window>> windows = new EnumMap<>(RouteGroup.class);

    PublicRateLimitFilter(PublicRateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        for (RouteGroup group : RouteGroup.values()) {
            windows.put(group, new ConcurrentHashMap<>());
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteGroup routeGroup = routeGroup(request);
        if (!properties.enabled() || routeGroup == null) {
            chain.doFilter(request, response);
            return;
        }

        Decision decision = consume(routeGroup, ClientAddressResolver.resolve(request), limitFor(routeGroup));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        // Cabecera y cuerpo salen del mismo valor: nunca pueden discrepar.
        long retryAfterSeconds = decision.retryAfterSeconds();
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                RateLimitedResponse.body(request.getRequestURI(), retryAfterSeconds));
    }

    private Decision consume(RouteGroup group, String key, int limit) {
        Map<String, Window> clientWindows = windows.get(group);
        long now = System.currentTimeMillis();
        long windowMillis = properties.windowSeconds() * 1_000L;
        Window current = clientWindows.get(key);
        if (current == null && clientWindows.size() >= properties.maxTrackedClients()) {
            removeExpired(clientWindows, now, windowMillis);
            if (clientWindows.size() >= properties.maxTrackedClients()) {
                return new Decision(false, 1);
            }
        }
        Window next = clientWindows.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.startedAtMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(previous.startedAtMillis(), previous.requests() + 1);
        });
        long remainingMillis = Math.max(1, windowMillis - (now - next.startedAtMillis()));
        return new Decision(next.requests() <= limit, (remainingMillis + 999) / 1_000);
    }

    private static void removeExpired(Map<String, Window> windows, long now, long windowMillis) {
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis() >= windowMillis);
    }

    private int limitFor(RouteGroup routeGroup) {
        return switch (routeGroup) {
            case LOGIN -> properties.loginPerWindow();
            case MFA -> properties.mfaPerWindow();
            case RECOVERY -> properties.recoveryPerWindow();
            case REGISTRATION -> properties.registrationPerWindow();
            case CHECKOUT -> properties.checkoutPerWindow();
            case TRACKING -> properties.trackingPerWindow();
            case CONTACT -> properties.contactPerWindow();
        };
    }

    private static RouteGroup routeGroup(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) && !"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(method) && ("/api/v1/auth/login".equals(path)
                || "/api/v1/auth/google".equals(path))) {
            return RouteGroup.LOGIN;
        }
        if ("POST".equalsIgnoreCase(method) && ("/api/v1/auth/mfa/verify".equals(path)
                || "/api/v1/auth/mfa/resend".equals(path))) {
            return RouteGroup.MFA;
        }
        if ("POST".equalsIgnoreCase(method) && ("/api/v1/auth/password/forgot".equals(path)
                || "/api/v1/auth/password/reset".equals(path)
                || "/api/v1/auth/activate-employee".equals(path)
                || "/api/v1/auth/email/verify".equals(path)
                || "/api/v1/auth/email/resend".equals(path))) {
            return RouteGroup.RECOVERY;
        }
        if ("POST".equalsIgnoreCase(method) && path.matches("/api/v1/public/[^/]+/auth/register")) {
            return RouteGroup.REGISTRATION;
        }
        if ("POST".equalsIgnoreCase(method) && path.matches("/api/v1/public/[^/]+/checkout")) {
            return RouteGroup.CHECKOUT;
        }
        if ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/public/[^/]+/tracking/[^/]+")) {
            return RouteGroup.TRACKING;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/public/contact-requests".equals(path)) {
            return RouteGroup.CONTACT;
        }
        return null;
    }
}
