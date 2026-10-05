package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flujo completo contra PostgreSQL: PENDING dentro de la transaccion, envio tras el commit, reintentos y
 * politica de remitente (plataforma vs tenant). La conexion a Gmail se sustituye por un cliente falso.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class EmailDeliveryServiceIntegrationTest {

    private static final String SECRET = "abcdEFGH12345678";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate tx;
    @Autowired private EmailDeliveryService deliveryService;
    @Autowired private EmailDeliveryRepository deliveries;
    @Autowired private TenantEmailSenderConfigRepository configs;
    @Autowired private TenantRepository tenants;
    @Autowired private EmailCredentialCrypto crypto;
    @Autowired private EmailDeliveryProperties properties;
    @Autowired private CapturingEmailSender platformSender;
    @Autowired private GmailSmtpEmailProvider tenantProvider;

    @MockitoBean private TenantMailSenderFactory senderFactory;

    private final List<MimeMessage> gmailSent = new CopyOnWriteArrayList<>();
    private volatile RuntimeException gmailFailure;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        gmailSent.clear();
        gmailFailure = null;
        tenant = persistTenant();
        given(senderFactory.create(any(), any())).willReturn(new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
                if (gmailFailure != null) {
                    throw gmailFailure;
                }
                gmailSent.addAll(List.of(mimeMessages));
            }
        });
    }

    @Test
    void platformEmailGoesThroughPlatformSenderAndStoresNoBody() {
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.PASSWORD_RESET, to, "Restablece", "token-secreto-123"));

        EmailDelivery delivery = awaitDelivery(to);

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getSentAt()).isNotNull();
        assertThat(delivery.getEncryptedPayload()).isNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(platformSender.awaitMessageTo(to).body()).isEqualTo("token-secreto-123");
        assertThat(gmailSent).isEmpty();
    }

    @Test
    void tenantEmailGoesThroughTenantAccountNotThePlatform() throws Exception {
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido WEB-1", "Tu pedido WEB-1"));

        EmailDelivery delivery = awaitDelivery(to);

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(gmailSent).singleElement().satisfies(mime ->
                assertThat(mime.getFrom()[0].toString()).contains("Mi Tienda", "tienda@gmail.com"));
        assertThat(platformSender.settledMessagesTo(to)).isEmpty();
        // El cuerpo no queda en claro en la BD: solo cifrado, ligado al tenant.
        assertThat(delivery.getEncryptedPayload()).isNotBlank().doesNotContain("Tu pedido WEB-1");
        assertThat(delivery.getSubject()).isEqualTo("Pedido WEB-1");
    }

    @Test
    void tenantWithoutSenderIsFailedAndNeverUsesThePlatformSender() {
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.DISPATCH_NOTIFICATION, to, "Despacho", "En camino"));

        EmailDelivery delivery = awaitDelivery(to);

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(delivery.getErrorCode()).isEqualTo("SENDER_NOT_CONFIGURED");
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(platformSender.settledMessagesTo(to)).isEmpty();
        assertThat(gmailSent).isEmpty();
    }

    @Test
    void senderInErrorIsTreatedAsNotConfigured() {
        saveConfig(tenant, EmailSenderStatus.ERROR);
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Hola"));

        EmailDelivery delivery = awaitDelivery(to);

        assertThat(delivery.getErrorCode()).isEqualTo("SENDER_NOT_CONFIGURED");
        assertThat(platformSender.settledMessagesTo(to)).isEmpty();
    }

    @Test
    void authenticationFailureMarksTheSenderInErrorWithoutRetry() {
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        gmailFailure = new MailAuthenticationException("535 " + SECRET);
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Hola"));

        EmailDelivery delivery = awaitDelivery(to);

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(delivery.getErrorCode()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(delivery.getNextAttemptAt()).isNull();
        TenantEmailSenderConfig config = configs.findByTenantId(tenant.getId()).orElseThrow();
        assertThat(config.getStatus()).isEqualTo(EmailSenderStatus.ERROR);
        assertThat(config.getLastFailureAt()).isNotNull();
        assertThat(delivery.getErrorCode()).doesNotContain(SECRET);
    }

    @Test
    void transportFailureIsRetriedWithBackoffUntilItSucceeds() {
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        gmailFailure = new MailSendException("timeout");
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Cuerpo del pedido"));

        EmailDelivery failed = awaitDelivery(to);
        assertThat(failed.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("TRANSPORT_ERROR");
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isAfter(failed.getLastAttemptAt());

        // Todavia no vence: el job no lo toca.
        assertThat(deliveryService.retryDue()).isZero();

        gmailFailure = null;
        makeDue(failed);
        assertThat(deliveryService.retryDue()).isGreaterThanOrEqualTo(1);

        EmailDelivery retried = deliveries.findById(failed.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getErrorCode()).isNull();
        assertThat(retried.getNextAttemptAt()).isNull();
        // El cuerpo se descifra desde el registro para reintentar.
        assertThat(gmailSent).hasSize(1);
    }

    @Test
    void retriesStopAtTheAttemptCap() {
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        gmailFailure = new MailSendException("timeout");
        String to = unique();
        request(EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Hola"));
        EmailDelivery delivery = awaitDelivery(to);

        while (delivery.getNextAttemptAt() != null) {
            makeDue(delivery);
            deliveryService.retryDue();
            delivery = deliveries.findById(delivery.getId()).orElseThrow();
        }

        assertThat(delivery.getAttempts()).isEqualTo(properties.maxAttempts());
        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(delivery.getErrorCode()).isEqualTo("TRANSPORT_ERROR");
        assertThat(gmailSent).isEmpty();
    }

    @Test
    void emailFailureNeverRollsBackTheBusinessOperation() {
        gmailFailure = new MailSendException("timeout");
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        String to = unique();
        String slug = "negocio-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            tenants.save(Tenant.builder().name("Negocio").slug(slug).status(TenantStatus.active)
                    .defaultCurrency("GTQ").timezone("America/Guatemala").build());
            publisher.publishEvent(new EmailRequestedEvent(
                    EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Hola")));
        });

        assertThat(awaitDelivery(to).getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(tenants.findBySlug(slug)).isPresent();
    }

    @Test
    void rolledBackOperationLeavesNoDeliveryAndSendsNothing() {
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        String to = unique();

        try {
            tx.executeWithoutResult(status -> {
                publisher.publishEvent(new EmailRequestedEvent(
                        EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Hola")));
                throw new IllegalStateException("la operacion de negocio fallo");
            });
        } catch (IllegalStateException expected) {
            // esperado
        }

        assertThat(platformSender.settledMessagesTo(to)).isEmpty();
        assertThat(gmailSent).isEmpty();
        assertThat(deliveriesTo(to)).isEmpty();
    }

    @Test
    void dueRowsAreClaimedByOnlyOneRetryAtATime() {
        saveConfig(tenant, EmailSenderStatus.VERIFIED);
        List<EmailDelivery> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String to = unique();
            gmailFailure = new MailSendException("timeout");
            request(EmailMessage.text(tenant.getId(), EmailPurpose.ORDER_CONFIRMATION, to, "Pedido", "Hola"));
            rows.add(awaitDelivery(to));
        }
        gmailFailure = null;
        rows.forEach(this::makeDue);

        int processed = deliveryService.retryDue();

        assertThat(processed).isGreaterThanOrEqualTo(3);
        rows.forEach(row -> assertThat(deliveries.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(EmailDeliveryStatus.SENT));
        assertThat(gmailSent).hasSize(3);
        // Segunda corrida: ya no hay nada vencido.
        assertThat(deliveryService.retryDue()).isZero();
    }

    private void request(EmailMessage message) {
        tx.executeWithoutResult(status -> publisher.publishEvent(new EmailRequestedEvent(message)));
    }

    private void makeDue(EmailDelivery delivery) {
        EmailDelivery stored = deliveries.findById(delivery.getId()).orElseThrow();
        stored.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveries.saveAndFlush(stored);
    }

    private void saveConfig(Tenant forTenant, EmailSenderStatus status) {
        EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(SECRET, forTenant.getId());
        TenantEmailSenderConfig config = TenantEmailSenderConfig.builder()
                .provider(EmailSenderProvider.GMAIL_SMTP)
                .senderEmail("tienda@gmail.com")
                .senderName("Mi Tienda")
                .encryptedCredential(encrypted.ciphertext())
                .credentialIv(encrypted.iv())
                .encryptionKeyVersion(encrypted.keyVersion())
                .status(status)
                .build();
        config.setTenantId(forTenant.getId());
        configs.saveAndFlush(config);
        tenantProvider.invalidate(forTenant.getId());
    }

    /** Espera a que el envio asincrono deje de estar PENDING. */
    private EmailDelivery awaitDelivery(String to) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            List<EmailDelivery> found = deliveriesTo(to);
            if (found.size() == 1 && found.getFirst().getStatus() != EmailDeliveryStatus.PENDING) {
                return found.getFirst();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("El envio a " + to + " no termino: " + deliveriesTo(to).size() + " registros");
    }

    private List<EmailDelivery> deliveriesTo(String to) {
        return deliveries.findAll().stream().filter(row -> row.getRecipient().equals(to)).toList();
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        return tenants.save(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private static String unique() {
        return "dest-" + UUID.randomUUID() + "@example.com";
    }
}
