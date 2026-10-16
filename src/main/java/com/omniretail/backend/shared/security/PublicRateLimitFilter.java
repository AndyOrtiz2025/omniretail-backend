package com.omniretail.backend.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
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
        login, mfa, recovery, registration, checkout, tracking
    }

    private record Window(long startedAtMillis, int requests) {
    }

    private record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final PublicRateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    PublicRateLimitFilter(PublicRateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteGroup routeGroup = routeGroup(request);
        if (!properties.enabled() || routeGroup == null) {
            chain.doFilter(request, response);
            return;
        }

        Decision decision = consume(clientAddress(request) + ':' + routeGroup.name(), limitFor(routeGroup));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", "Too Many Requests",
                "code", "RATE_LIMITED",
                "message", "Demasiados intentos. Intenta de nuevo en unos momentos.",
                "path", request.getRequestURI(),
                "timestamp", Instant.now().toString()));
    }

    private Decision consume(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowMillis = properties.windowSeconds() * 1_000L;
        Window current = windows.get(key);
        if (current == null && windows.size() >= properties.maxTrackedClients()) {
            removeExpired(now, windowMillis);
            if (windows.size() >= properties.maxTrackedClients()) {
                return new Decision(false, 1);
            }
        }
        Window next = windows.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.startedAtMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(previous.startedAtMillis(), previous.requests() + 1);
        });
        long remainingMillis = Math.max(1, windowMillis - (now - next.startedAtMillis()));
        return new Decision(next.requests() <= limit, (remainingMillis + 999) / 1_000);
    }

    private void removeExpired(long now, long windowMillis) {
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis() >= windowMillis);
    }

    private int limitFor(RouteGroup routeGroup) {
        return switch (routeGroup) {
            case login -> properties.loginPerWindow();
            case mfa -> properties.mfaPerWindow();
            case recovery -> properties.recoveryPerWindow();
            case registration -> properties.registrationPerWindow();
            case checkout -> properties.checkoutPerWindow();
            case tracking -> properties.trackingPerWindow();
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
            return RouteGroup.login;
        }
        if ("POST".equalsIgnoreCase(method) && ("/api/v1/auth/mfa/verify".equals(path)
                || "/api/v1/auth/mfa/resend".equals(path))) {
            return RouteGroup.mfa;
        }
        if ("POST".equalsIgnoreCase(method) && ("/api/v1/auth/password/forgot".equals(path)
                || "/api/v1/auth/password/reset".equals(path)
                || "/api/v1/auth/activate-employee".equals(path))) {
            return RouteGroup.recovery;
        }
        if ("POST".equalsIgnoreCase(method) && path.matches("/api/v1/public/[^/]+/auth/register")) {
            return RouteGroup.registration;
        }
        if ("POST".equalsIgnoreCase(method) && path.matches("/api/v1/public/[^/]+/checkout")) {
            return RouteGroup.checkout;
        }
        if ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/public/[^/]+/tracking/[^/]+")) {
            return RouteGroup.tracking;
        }
        return null;
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrustedProxy(request.getRemoteAddr())) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        String first = forwarded.substring(0, comma >= 0 ? comma : forwarded.length()).trim();
        if (!first.matches("[0-9a-fA-F:.]+")) {
            return request.getRemoteAddr();
        }
        try {
            return InetAddress.getByName(first).getHostAddress();
        } catch (UnknownHostException ex) {
            return request.getRemoteAddr();
        }
    }

    private static boolean isTrustedProxy(String remoteAddress) {
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
