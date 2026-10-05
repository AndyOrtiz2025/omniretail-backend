package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class GmailSmtpEmailProviderTest {

    private static final String SECRET = "abcdEFGH12345678";

    private final TenantEmailSenderConfigRepository configs = mock(TenantEmailSenderConfigRepository.class);
    private final EmailCredentialCrypto crypto =
            new EmailCredentialCrypto(new EmailCredentialProperties("una-clave-de-correo-de-pruebas-de-32-bytes"));
    private final List<String[]> createdWith = new ArrayList<>();
    private final List<MimeMessage> sent = new ArrayList<>();
    private RuntimeException sendFailure;
    private GmailSmtpEmailProvider provider;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        provider = new GmailSmtpEmailProvider(configs, crypto, (username, password) -> {
            createdWith.add(new String[] {username, password});
            return new JavaMailSenderImpl() {
                @Override
                protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
                    if (sendFailure != null) {
                        throw sendFailure;
                    }
                    sent.addAll(List.of(mimeMessages));
                }
            };
        });
    }

    @Test
    void sendsWithTheTenantAccountAndNameAsFrom() throws Exception {
        givenConfig(EmailSenderStatus.CONFIGURED);

        provider.send(message());

        assertThat(createdWith).singleElement().satisfies(args -> {
            assertThat(args[0]).isEqualTo("tienda@gmail.com");
            assertThat(args[1]).isEqualTo(SECRET);
        });
        assertThat(sent).singleElement().satisfies(mime -> {
            assertThat(mime.getFrom()[0].toString()).contains("Mi Tienda").contains("tienda@gmail.com");
            assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("cliente@example.com");
        });
    }

    @Test
    void reusesTheCachedClientUntilTheConfigChangesOrIsInvalidated() {
        TenantEmailSenderConfig config = givenConfig(EmailSenderStatus.CONFIGURED);

        provider.send(message());
        provider.send(message());
        assertThat(createdWith).hasSize(1);

        provider.invalidate(tenantId);
        provider.send(message());
        assertThat(createdWith).hasSize(2);

        config.setSenderEmail("otra@gmail.com");
        provider.send(message());
        assertThat(createdWith).hasSize(3);
    }

    @Test
    void tenantWithoutConfigOrInErrorNeverSends() {
        when(configs.findByTenantId(tenantId)).thenReturn(Optional.empty());
        assertThat(assertThrows(EmailDeliveryException.class, () -> provider.send(message())).getCode())
                .isEqualTo(EmailDeliveryException.SENDER_NOT_CONFIGURED);

        givenConfig(EmailSenderStatus.ERROR);
        assertThat(assertThrows(EmailDeliveryException.class, () -> provider.send(message())).getCode())
                .isEqualTo(EmailDeliveryException.SENDER_NOT_CONFIGURED);
        assertThat(sent).isEmpty();
    }

    @Test
    void sendTestWorksEvenWhenTheAccountIsInError() {
        givenConfig(EmailSenderStatus.ERROR);

        provider.sendTest(message());

        assertThat(sent).hasSize(1);
    }

    @Test
    void mapsMailFailuresToSanitizedCodes() {
        givenConfig(EmailSenderStatus.CONFIGURED);

        sendFailure = new MailAuthenticationException("535 5.7.8 Username and Password not accepted " + SECRET);
        EmailDeliveryException auth = assertThrows(EmailDeliveryException.class, () -> provider.send(message()));
        sendFailure = new MailSendException("Couldn't connect to host " + SECRET);
        EmailDeliveryException transport = assertThrows(EmailDeliveryException.class, () -> provider.send(message()));

        assertThat(auth.getCode()).isEqualTo(EmailDeliveryException.AUTHENTICATION_FAILED);
        assertThat(transport.getCode()).isEqualTo(EmailDeliveryException.TRANSPORT_ERROR);
        for (EmailDeliveryException error : List.of(auth, transport)) {
            assertThat(error.getMessage()).doesNotContain(SECRET, "javax", "jakarta", "535");
            assertThat(error.getCause()).isNull();
        }
    }

    @Test
    void undecryptableCredentialIsReportedWithoutDetails() {
        TenantEmailSenderConfig config = givenConfig(EmailSenderStatus.CONFIGURED);
        config.setEncryptedCredential("%%corrupto%%");

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class, () -> provider.send(message()));

        assertThat(error.getCode()).isEqualTo(EmailDeliveryException.CREDENTIAL_UNREADABLE);
        assertThat(error.getCause()).isNull();
    }

    private TenantEmailSenderConfig givenConfig(EmailSenderStatus status) {
        EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(SECRET, tenantId);
        TenantEmailSenderConfig config = TenantEmailSenderConfig.builder()
                .provider(EmailSenderProvider.GMAIL_SMTP)
                .senderEmail("tienda@gmail.com")
                .senderName("Mi Tienda")
                .encryptedCredential(encrypted.ciphertext())
                .credentialIv(encrypted.iv())
                .encryptionKeyVersion(encrypted.keyVersion())
                .status(status)
                .build();
        config.setTenantId(tenantId);
        when(configs.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        return config;
    }

    private EmailMessage message() {
        return EmailMessage.text(
                tenantId, EmailPurpose.ORDER_CONFIRMATION, "cliente@example.com", "Pedido", "Tu pedido fue confirmado");
    }
}
