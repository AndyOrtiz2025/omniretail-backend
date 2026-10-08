package com.omniretail.backend.shared.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Reemplaza el envio SMTP en los tests y guarda los correos. El envio real es asincrono (despues del
 * commit), por eso las consultas esperan un poco antes de responder.
 * Uso: {@code @Import(CapturingEmailSender.Config.class)} y {@code @Autowired CapturingEmailSender}.
 */
public class CapturingEmailSender implements PlatformEmailSender {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration QUIET_PERIOD = Duration.ofMillis(500);

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    @Override
    public void send(EmailMessage message) {
        if (failure != null) {
            throw failure;
        }
        sent.add(message);
    }

    /** Hace fallar los envios siguientes con {@code failure} (null vuelve a enviar). El bean es compartido: restablecerlo. */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    /** Espera hasta que {@code to} haya recibido {@code count} correos y los devuelve. */
    public List<EmailMessage> awaitMessagesTo(String to, int count) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            List<EmailMessage> found = messagesTo(to);
            if (found.size() >= count) {
                return found;
            }
            pause(Duration.ofMillis(20));
        }
        throw new AssertionError("Se esperaban " + count + " correos para " + to + " y llegaron " + messagesTo(to).size());
    }

    public EmailMessage awaitMessageTo(String to) {
        return awaitMessagesTo(to, 1).getLast();
    }

    /** Correos para {@code to} tras dejar pasar un momento, para confirmar que no llega ninguno mas. */
    public List<EmailMessage> settledMessagesTo(String to) {
        pause(QUIET_PERIOD);
        return messagesTo(to);
    }

    private List<EmailMessage> messagesTo(String to) {
        return sent.stream().filter(message -> message.to().equals(to)).toList();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }
}
