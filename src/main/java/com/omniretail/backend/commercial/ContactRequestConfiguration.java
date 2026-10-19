package com.omniretail.backend.commercial;

import com.omniretail.backend.shared.notification.OutgoingMailProperties;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Solo existe con el servicio comercial habilitado. Si la configuracion esta incompleta la aplicacion NO arranca:
 * un endpoint publico que acepta datos y no puede avisar a nadie es peor que un endpoint ausente. El mensaje
 * nombra las propiedades que faltan, nunca sus valores.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ContactRequestsEnabled
class ContactRequestConfiguration {

    static final int MIN_SECRET_LENGTH = 32;
    private static final String PREFIX = "app.commercial.contact-requests.";

    private final ContactRequestProperties properties;
    private final OutgoingMailProperties mailProperties;
    private final Environment environment;

    ContactRequestConfiguration(
            ContactRequestProperties properties, OutgoingMailProperties mailProperties, Environment environment) {
        this.properties = properties;
        this.mailProperties = mailProperties;
        this.environment = environment;
    }

    @PostConstruct
    void requireCompleteConfiguration() {
        List<String> problems = problems(properties, mailProperties, environment.getProperty("spring.mail.host"));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Solicitudes comerciales habilitadas con configuracion incompleta o invalida: "
                            + String.join(", ", problems));
        }
        // Host, puerto y autenticacion no son secretos: permiten detectar un SMTP de desarrollo (localhost:1025)
        // aceptando correos que nunca llegaran a una bandeja real. Nunca se registran usuario, clave ni destinatarios.
        log.info("Correo comercial habilitado smtpHost={} smtpPort={} smtpAuth={} smtpStartTls={} captcha={}",
                environment.getProperty("spring.mail.host"),
                environment.getProperty("spring.mail.port"),
                environment.getProperty("spring.mail.properties.mail.smtp.auth"),
                environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable"),
                properties.captcha().enabled());
    }

    static List<String> problems(
            ContactRequestProperties properties, OutgoingMailProperties mailProperties, String mailHost) {
        List<String> problems = new ArrayList<>();
        if (!properties.teamNotificationTo().matches("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$")) {
            problems.add(PREFIX + "team-notification-to");
        }
        if (properties.ipHashSecret().length() < MIN_SECRET_LENGTH) {
            problems.add(PREFIX + "ip-hash-secret (minimo " + MIN_SECRET_LENGTH + " caracteres)");
        }
        if (mailProperties == null || mailProperties.from() == null || mailProperties.from().isBlank()) {
            problems.add("app.mail.from");
        }
        if (mailHost == null || mailHost.isBlank()) {
            problems.add("spring.mail.host");
        }
        ContactRequestProperties.Captcha captcha = properties.captcha();
        if (captcha.enabled()) {
            if (captcha.secret().isBlank()) {
                problems.add(PREFIX + "captcha.secret");
            }
            if (!captcha.verifyUrl().startsWith("https://")) {
                problems.add(PREFIX + "captcha.verify-url (debe ser https)");
            }
        }
        return problems;
    }

    @Bean
    CaptchaVerifier captchaVerifier(JsonMapper jsonMapper) {
        return properties.captcha().enabled()
                ? new TurnstileCaptchaVerifier(properties.captcha(), jsonMapper)
                : (token, clientIp) -> true;
    }
}
