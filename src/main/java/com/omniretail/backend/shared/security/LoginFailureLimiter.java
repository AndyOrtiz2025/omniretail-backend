package com.omniretail.backend.shared.security;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Logins fallidos por IP + email normalizado, en memoria y con ventana fija. Complementa al bloqueo por cuenta
 * de {@code LoginAttemptService} (B-01), que no cambia: aquel vive en la BD y cuenta desde cualquier IP, pero no
 * ve correos inexistentes. Este frena a una IP que insiste con un mismo correo, exista o no, sin llegar a bcrypt
 * ni a la BD. Como responde igual para correos reales e inexistentes, no revela si la cuenta existe.
 *
 * <ul>
 *   <li>{@link #checkAllowed}: con {@code maxFailures} fallos en la ventana lanza {@link RateLimitedException}.</li>
 *   <li>{@link #recordFailure}: un {@code INVALID_CREDENTIALS} suma un fallo.</li>
 *   <li>{@link #clear}: solo cuando se entrega la sesion ({@code /auth/login} sin MFA o {@code /auth/mfa/verify}).
 *       Abrir el desafio MFA no suma ni limpia, igual que B-01 no corta la racha hasta el codigo correcto.</li>
 * </ul>
 *
 * <p>La clave normaliza el email (trim + minusculas), igual que {@code LoginRequest}.
 */
@Component
public class LoginFailureLimiter {

    private record Window(long startedAtMillis, int failures) {
    }

    private final LoginFailureLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public LoginFailureLimiter(LoginFailureLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginFailureLimiter(LoginFailureLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkAllowed(String clientAddress, String email) {
        if (!properties.enabled()) {
            return;
        }
        long now = clock.millis();
        Window current = windows.get(key(clientAddress, email));
        if (current == null || expired(current, now)) {
            return;
        }
        if (current.failures() >= properties.maxFailures()) {
            long remainingMillis = Math.max(1, windowMillis() - (now - current.startedAtMillis()));
            throw new RateLimitedException((remainingMillis + 999) / 1_000);
        }
    }

    public void recordFailure(String clientAddress, String email) {
        if (!properties.enabled()) {
            return;
        }
        long now = clock.millis();
        String key = key(clientAddress, email);
        if (!windows.containsKey(key) && windows.size() >= properties.maxTrackedKeys()) {
            windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
            if (windows.size() >= properties.maxTrackedKeys()) {
                return; // Sin cupo: el limite por IP del filtro y B-01 siguen protegiendo.
            }
        }
        windows.compute(key, (ignored, previous) -> previous == null || expired(previous, now)
                ? new Window(now, 1)
                : new Window(previous.startedAtMillis(), previous.failures() + 1));
    }

    public void clear(String clientAddress, String email) {
        if (properties.enabled()) {
            windows.remove(key(clientAddress, email));
        }
    }

    private boolean expired(Window window, long now) {
        return now - window.startedAtMillis() >= windowMillis();
    }

    private long windowMillis() {
        return properties.windowSeconds() * 1_000L;
    }

    private static String key(String clientAddress, String email) {
        return clientAddress + "|" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }
}
