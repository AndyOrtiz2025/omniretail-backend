package com.omniretail.backend.shared.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reintentos de correos operativos fallidos por transporte ({@code app.mail.delivery.*}).
 *
 * @param retryEnabled apaga el job (los tests lo invocan a mano).
 * @param retryInterval cada cuanto corre el job.
 * @param initialDelay espera antes de que el job tome un envio recien creado (la via normal es el envio tras commit).
 * @param baseBackoff espera tras el primer fallo; se duplica en cada intento.
 * @param maxAttempts tope de intentos por correo.
 * @param batchSize filas que toma el job por corrida.
 */
@ConfigurationProperties("app.mail.delivery")
public record EmailDeliveryProperties(
        Boolean retryEnabled,
        Duration retryInterval,
        Duration initialDelay,
        Duration baseBackoff,
        Integer maxAttempts,
        Integer batchSize) {

    public EmailDeliveryProperties {
        retryEnabled = retryEnabled == null || retryEnabled;
        retryInterval = retryInterval == null ? Duration.ofMinutes(1) : retryInterval;
        initialDelay = initialDelay == null ? Duration.ofMinutes(2) : initialDelay;
        baseBackoff = baseBackoff == null ? Duration.ofMinutes(1) : baseBackoff;
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? 5 : maxAttempts;
        batchSize = batchSize == null || batchSize < 1 ? 20 : batchSize;
    }
}
