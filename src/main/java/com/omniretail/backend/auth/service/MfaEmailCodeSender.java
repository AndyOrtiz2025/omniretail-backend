package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.auth.entity.MfaEnrollment;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Codigos de verificacion por correo (metodo {@code email}): los genera, aplica los limites de envio y pide
 * el correo, que sale despues del commit (EmailRequestedListener). El codigo solo viaja en el cuerpo del
 * correo: nunca en respuestas ni logs, y el correo no lleva enlaces.
 *
 * <p>Limites por usuario, sumando todos los flujos (login, reenvio, activacion y cambio de contrasena): 60 s
 * entre envios y 10 correos por hora. El llamador debe tener bloqueado el enrollment (FOR UPDATE), donde
 * se llevan los contadores.
 */
@Component
@RequiredArgsConstructor
public class MfaEmailCodeSender {

    /** MFA_CHALLENGE_EXPIRATION_MINUTES: la misma vigencia que el desafio de la app. */
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration MIN_WAIT_BETWEEN_SENDS = Duration.ofSeconds(60);
    static final Duration SEND_WINDOW = Duration.ofHours(1);
    static final int MAX_SENDS_PER_WINDOW = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationEventPublisher eventPublisher;

    /** Para que se envia el codigo; arma la frase del correo. */
    public enum Purpose {
        LOGIN("iniciar sesión"),
        ENROLLMENT("activar la verificación en dos pasos"),
        PASSWORD_CHANGE("cambiar tu contraseña");

        private final String action;

        Purpose(String action) {
            this.action = action;
        }
    }

    /** {@code MFA_CODE_DIGITS} digitos, con ceros a la izquierda. */
    public static String newCode() {
        return String.format("%0" + Totp.DIGITS + "d", RANDOM.nextInt((int) Math.pow(10, Totp.DIGITS)));
    }

    /** Si se puede enviar otro correo ahora sin pasar la espera minima ni el tope por hora. */
    public boolean canSend(MfaEnrollment enrollment, Instant now) {
        Instant lastSent = enrollment.getEmailCodeSentAt();
        if (lastSent != null && Duration.between(lastSent, now).compareTo(MIN_WAIT_BETWEEN_SENDS) < 0) {
            return false;
        }
        return windowExpired(enrollment, now) || enrollment.getEmailCodeWindowCount() < MAX_SENDS_PER_WINDOW;
    }

    /** Cuenta el envio y pide el correo. Llamar solo despues de {@link #canSend}. */
    public void send(MfaEnrollment enrollment, User user, String code, Purpose purpose, Instant now) {
        if (windowExpired(enrollment, now)) {
            enrollment.setEmailCodeWindowStartedAt(now);
            enrollment.setEmailCodeWindowCount(0);
        }
        enrollment.setEmailCodeWindowCount(enrollment.getEmailCodeWindowCount() + 1);
        enrollment.setEmailCodeSentAt(now);
        eventPublisher.publishEvent(new EmailRequestedEvent(message(user, code, purpose)));
    }

    private static boolean windowExpired(MfaEnrollment enrollment, Instant now) {
        Instant started = enrollment.getEmailCodeWindowStartedAt();
        return started == null || Duration.between(started, now).compareTo(SEND_WINDOW) >= 0;
    }

    /** Texto simple, sin enlaces: solo el codigo. */
    private static EmailMessage message(User user, String code, Purpose purpose) {
        String body = """
                Hola %s:

                Tu código para %s es: %s

                Vence en %d minutos. No lo compartas con nadie.

                Si no fuiste tú, cambia tu contraseña.
                """.formatted(user.getName(), purpose.action, code, CODE_TTL.toMinutes());
        return EmailMessage.text(
                user.getTenantId(), EmailPurpose.MFA_CODE, user.getEmail(), "Tu código de verificación", body);
    }
}
