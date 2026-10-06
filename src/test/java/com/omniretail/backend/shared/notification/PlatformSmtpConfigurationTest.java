package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * El SMTP de plataforma sale del application.yaml real: sin variables queda como Mailpit (sin auth ni
 * STARTTLS) y con MAIL_SMTP_AUTH / MAIL_SMTP_STARTTLS en true queda listo para smtp.gmail.com:587.
 */
class PlatformSmtpConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                // Ni el entorno de la maquina ni el .env deben cambiar el resultado del test.
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    new YamlPropertySourceLoader()
                            .load("application.yaml", new ClassPathResource("application.yaml"))
                            .forEach(sources::addLast);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });

    @Test
    void defaultsKeepMailpitWithoutAuthOrStarttls() {
        runner.run(context -> {
            JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
            Properties props = sender.getJavaMailProperties();

            assertThat(sender.getHost()).isEqualTo("localhost");
            assertThat(sender.getPort()).isEqualTo(1025);
            assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("false");
            assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
            assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
            assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
            assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("10000");
            assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
        });
    }

    @Test
    void gmailVariablesEnableAuthAndRequireStarttls() {
        runner.withPropertyValues(
                        "MAIL_HOST=smtp.gmail.com",
                        "MAIL_PORT=587",
                        "MAIL_USERNAME=grupo@gmail.com",
                        "MAIL_PASSWORD=abcdefghijklmnop",
                        "MAIL_SMTP_AUTH=true",
                        "MAIL_SMTP_STARTTLS=true",
                        "MAIL_SMTP_TIMEOUT_MILLIS=5000")
                .run(context -> {
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    Properties props = sender.getJavaMailProperties();

                    assertThat(sender.getHost()).isEqualTo("smtp.gmail.com");
                    assertThat(sender.getPort()).isEqualTo(587);
                    assertThat(sender.getUsername()).isEqualTo("grupo@gmail.com");
                    assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
                    assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
                    assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
                    assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
                    assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("5000");
                    assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
                });
    }
}
