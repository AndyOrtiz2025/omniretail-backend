package com.omniretail.backend.commercial;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de {@code app.commercial.contact-requests.*}. Existe siempre (es solo un registro de valores);
 * lo que depende de {@code enabled} son los componentes, ver {@link ContactRequestsEnabled}.
 */
@ConfigurationProperties("app.commercial.contact-requests")
public record ContactRequestProperties(
        Boolean enabled,
        String teamNotificationTo,
        String ipHashSecret,
        Integer maxBodyBytes,
        Integer perIpPerDay,
        Integer perEmailPerDay,
        Integer globalPerDay,
        Integer retentionDays,
        Integer spamRetentionDays,
        Integer mailMaxAttempts,
        Duration mailInitialDelay,
        Duration mailBaseBackoff,
        Duration mailRetryInterval,
        String purgeCron,
        Captcha captcha) {

    public ContactRequestProperties {
        enabled = enabled != null && enabled;
        teamNotificationTo = teamNotificationTo == null ? "" : teamNotificationTo.trim();
        ipHashSecret = ipHashSecret == null ? "" : ipHashSecret;
        maxBodyBytes = positive(maxBodyBytes, 16_384);
        perIpPerDay = positive(perIpPerDay, 10);
        perEmailPerDay = positive(perEmailPerDay, 3);
        globalPerDay = positive(globalPerDay, 200);
        retentionDays = positive(retentionDays, 365);
        spamRetentionDays = positive(spamRetentionDays, 30);
        mailMaxAttempts = positive(mailMaxAttempts, 5);
        mailInitialDelay = mailInitialDelay == null ? Duration.ofMinutes(2) : mailInitialDelay;
        mailBaseBackoff = mailBaseBackoff == null ? Duration.ofMinutes(5) : mailBaseBackoff;
        mailRetryInterval = mailRetryInterval == null ? Duration.ofMinutes(1) : mailRetryInterval;
        purgeCron = purgeCron == null || purgeCron.isBlank() ? "0 30 3 * * *" : purgeCron;
        captcha = captcha == null ? new Captcha(null, null, null, null) : captcha;
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    /** Verificacion anti-robots (Cloudflare Turnstile por defecto). Apagada salvo que se configure. */
    public record Captcha(Boolean enabled, String secret, String verifyUrl, Duration timeout) {

        public Captcha {
            enabled = enabled != null && enabled;
            secret = secret == null ? "" : secret;
            verifyUrl = verifyUrl == null || verifyUrl.isBlank()
                    ? "https://challenges.cloudflare.com/turnstile/v0/siteverify"
                    : verifyUrl.trim();
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }
    }
}
