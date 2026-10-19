package com.omniretail.backend.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.notification.EmailDeliveryException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.TenantEmailSender;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servicio comercial HABILITADO contra PostgreSQL. El SMTP se sustituye por {@link CapturingEmailSender}; el
 * reintento programado se aleja para que las pruebas lo invoquen a mano.
 */
@SpringBootTest(properties = {
    "app.commercial.contact-requests.enabled=true",
    "app.commercial.contact-requests.team-notification-to=equipo@marjym.test",
    "app.commercial.contact-requests.ip-hash-secret=prueba-secreto-solo-para-tests-0123456789",
    "app.commercial.contact-requests.per-ip-per-day=100000",
    "app.commercial.contact-requests.per-email-per-day=3",
    "app.commercial.contact-requests.global-per-day=100000",
    "app.commercial.contact-requests.mail-retry-interval=PT1H"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class ContactRequestIntegrationTest {

    private static final String TEAM = "equipo@marjym.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapturingEmailSender emails;
    @Autowired private ContactRequestMailService mailService;
    @Autowired private ContactRequestPurgeJob purgeJob;
    @MockitoBean private TenantEmailSender tenantEmailSender;

    @AfterEach
    void restoreTransport() {
        emails.failWith(null);
    }

    // ------------------------------------------------------------ registro y correos

    @Test
    void registersTheRequestAnswers202AndSendsBothEmailsAfterCommit() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        Map<String, Object> body = ContactRequestPayloads.valid(email, "Ferretería <b>Central</b>");
        body.put("fullName", "Ana <script>alert(1)</script>");
        long tenantsBefore = count("SELECT COUNT(*) FROM tenants");
        long usersBefore = count("SELECT COUNT(*) FROM users");
        long subscriptionsBefore = count("SELECT COUNT(*) FROM tenant_subscriptions");

        MvcResult result = send(body, null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        UUID requestId = requestId(result);
        // Contenido de la respuesta: solo identificador y estado, sin datos personales.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(email, "Ana");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", requestId);
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat(row.get("email")).isEqualTo(email);
        assertThat(row.get("ip_hash")).asString().hasSize(64).doesNotContain("127.0.0.1");
        assertThat(row.get("modules")).isEqualTo("inventory,pos");
        // Sin usuarios, tenants ni suscripciones nuevos.
        assertThat(count("SELECT COUNT(*) FROM tenants")).isEqualTo(tenantsBefore);
        assertThat(count("SELECT COUNT(*) FROM users")).isEqualTo(usersBefore);
        assertThat(count("SELECT COUNT(*) FROM tenant_subscriptions")).isEqualTo(subscriptionsBefore);

        EmailMessage confirmation = emails.awaitMessageTo(email);
        assertThat(confirmation.purpose()).isEqualTo(EmailPurpose.CONTACT_CONFIRMATION);
        assertThat(confirmation.tenantId()).isNull();
        assertThat(confirmation.purpose().scope()).isEqualTo(EmailPurpose.Scope.PLATFORM);
        assertThat(confirmation.body()).contains(requestId.toString());
        // El texto libre va escapado en el HTML y la confirmacion no repite los comentarios.
        assertThat(confirmation.html()).doesNotContain("<script>", "<b>Central</b>").contains("&lt;script&gt;");
        assertThat(confirmation.body()).doesNotContain("demostración");
        EmailMessage notice = emails.awaitMessageTo(TEAM);
        assertThat(notice.purpose()).isEqualTo(EmailPurpose.CONTACT_TEAM_NOTIFICATION);
        assertThat(notice.html()).doesNotContain("<script>");
        assertThat(notice.body()).contains(requestId.toString(), email);

        awaitMailStatus(requestId, "SENT");
        Map<String, Object> sent = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", requestId);
        assertThat(sent.get("team_mail_sent_at")).isNotNull();
        assertThat(sent.get("confirmation_mail_sent_at")).isNotNull();
    }

    @Test
    void neverUsesTheTenantSender() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        send(ContactRequestPayloads.valid(email, "Negocio plataforma"), null).andExpect(status().isAccepted());
        emails.awaitMessageTo(email);

        org.mockito.Mockito.verifyNoInteractions(tenantEmailSender);
    }

    // ------------------------------------------------------------ idempotencia y deduplicacion

    @Test
    void theSameIdempotencyKeyReturnsTheSameRequestWithoutNewRowsOrEmails() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        UUID key = UUID.randomUUID();
        UUID first = requestId(send(ContactRequestPayloads.valid(email, "Negocio idem"), key)
                .andExpect(status().isAccepted()).andReturn());
        emails.awaitMessageTo(email);

        // Mismo identificador aunque cambien los datos: la identidad es la clave.
        Map<String, Object> changed = ContactRequestPayloads.valid(email, "Otro negocio distinto");
        UUID second = requestId(send(changed, key).andExpect(status().isAccepted()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM contact_requests WHERE email = '" + email + "'")).isOne();
        assertThat(emails.settledMessagesTo(email)).hasSize(1);
    }

    @Test
    void theSameEmailAndBusinessTheSameDayIsNotRegisteredTwice() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        Map<String, Object> body = ContactRequestPayloads.valid(email, "Negocio Repetido");
        UUID first = requestId(send(body, null).andExpect(status().isAccepted()).andReturn());
        emails.awaitMessageTo(email);
        // Variaciones de mayusculas y espacios del mismo negocio y correo.
        Map<String, Object> again = ContactRequestPayloads.valid(email.toUpperCase(), "  negocio   repetido ");

        UUID second = requestId(send(again, null).andExpect(status().isAccepted()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM contact_requests WHERE email = '" + email + "'")).isOne();
        assertThat(emails.settledMessagesTo(email)).hasSize(1);
    }

    @Test
    void simultaneousRequestsWithTheSameKeyProduceOneRowOneRequestIdAndOneEmail() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        UUID key = UUID.randomUUID();
        String body = jsonMapper.writeValueAsString(ContactRequestPayloads.valid(email, "Negocio concurrente"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<UUID>> calls = java.util.stream.IntStream.range(0, 8)
                    .<Callable<UUID>>mapToObj(i -> () -> requestId(mockMvc.perform(post(ContactRequestPayloads.PATH)
                            .contentType(APPLICATION_JSON)
                            .header("Idempotency-Key", key.toString())
                            .content(body))
                            .andExpect(status().isAccepted()).andReturn()))
                    .toList();
            List<UUID> ids = new java.util.ArrayList<>();
            for (Future<UUID> future : pool.invokeAll(calls)) {
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(8).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM contact_requests WHERE email = '" + email + "'")).isOne();
        emails.awaitMessageTo(email);
        assertThat(emails.settledMessagesTo(email)).hasSize(1);
        assertThat(emails.settledMessagesTo(TEAM).stream()
                .filter(message -> message.body().contains(email))).hasSize(1);
    }

    // ------------------------------------------------------------ honeypot, validacion y cuotas

    @Test
    void aFilledHoneypotIsStoredAsSpamAnswersTheSameAndSendsNoEmail() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        Map<String, Object> body = ContactRequestPayloads.valid(email, "Negocio robot");
        body.put("website", "http://spam.example");

        UUID id = requestId(send(body, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED")).andReturn());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("SPAM");
        assertThat(row.get("team_mail_status")).isEqualTo("NOT_REQUIRED");
        assertThat(row.get("confirmation_mail_status")).isEqualTo("NOT_REQUIRED");
        assertThat(emails.settledMessagesTo(email)).isEmpty();
    }

    @Test
    void invalidRequestsAreRejectedWithFieldErrorsAndStoreNothing() throws Exception {
        Long before = count("SELECT COUNT(*) FROM contact_requests");

        Map<String, Object> noConsent = ContactRequestPayloads.valid();
        noConsent.put("consentAccepted", false);
        send(noConsent, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.consentAccepted").exists());

        Map<String, Object> badEmail = ContactRequestPayloads.valid();
        badEmail.put("email", "no-es-un-correo");
        send(badEmail, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.email").exists());

        Map<String, Object> controlChars = ContactRequestPayloads.valid();
        controlChars.put("fullName", "Ana\r\nBcc: victima@example.test");
        send(controlChars, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.fullName").exists());

        Map<String, Object> badPhone = ContactRequestPayloads.valid();
        badPhone.put("phone", "abc");
        send(badPhone, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.phone").exists());

        Map<String, Object> longMessage = ContactRequestPayloads.valid();
        longMessage.put("message", "x".repeat(1001));
        send(longMessage, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.message").exists());

        Map<String, Object> badIndustry = ContactRequestPayloads.valid();
        badIndustry.put("industry", "mineria-espacial");
        send(badIndustry, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.industry").exists());

        Map<String, Object> badModule = ContactRequestPayloads.valid();
        badModule.put("modules", List.of("inventory", "crear-tenant"));
        send(badModule, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.modules").exists());

        Map<String, Object> repeatedModule = ContactRequestPayloads.valid();
        repeatedModule.put("modules", List.of("pos", "pos"));
        send(repeatedModule, null).andExpect(status().isBadRequest());

        Map<String, Object> noModules = ContactRequestPayloads.valid();
        noModules.put("modules", List.of());
        send(noModules, null).andExpect(status().isBadRequest());

        send(ContactRequestPayloads.valid(), "no-es-uuid").andExpect(status().isBadRequest());

        assertThat(count("SELECT COUNT(*) FROM contact_requests")).isEqualTo(before);
    }

    @Test
    void aBodyAboveTheLimitIsRejectedBeforeBeingRead() throws Exception {
        Long before = count("SELECT COUNT(*) FROM contact_requests");

        mockMvc.perform(post(ContactRequestPayloads.PATH)
                        .contentType(APPLICATION_JSON)
                        .content("{\"padding\":\"" + "x".repeat(20_000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));

        assertThat(count("SELECT COUNT(*) FROM contact_requests")).isEqualTo(before);
    }

    @Test
    void theSameEmailCannotFloodTheFormAndTheTeam() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        for (int i = 0; i < 3; i++) {
            send(ContactRequestPayloads.valid(email, "Negocio " + i), null).andExpect(status().isAccepted());
        }

        send(ContactRequestPayloads.valid(email, "Negocio 4"), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CONTACT_QUOTA_EXCEEDED"));

        assertThat(count("SELECT COUNT(*) FROM contact_requests WHERE email = '" + email + "'")).isEqualTo(3);
    }

    // ------------------------------------------------------------ SMTP, reintentos

    @Test
    void anSmtpFailureKeepsTheRequestPendingForRetryAndNeverClaimsDelivery() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        emails.failWith(new EmailDeliveryException(EmailDeliveryException.TRANSPORT_ERROR));

        MvcResult result = send(ContactRequestPayloads.valid(email, "Negocio sin SMTP"), null)
                .andExpect(status().isAccepted()).andReturn();
        UUID id = requestId(result);

        awaitMailStatus(id, "FAILED");
        Map<String, Object> failed = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", id);
        assertThat(failed.get("status")).isEqualTo("RECEIVED");
        assertThat(failed.get("confirmation_mail_sent_at")).isNull();
        assertThat(failed.get("team_mail_sent_at")).isNull();
        assertThat(failed.get("confirmation_mail_attempts")).isEqualTo(1);
        assertThat(failed.get("confirmation_mail_error_code")).isEqualTo(EmailDeliveryException.TRANSPORT_ERROR);
        assertThat(failed.get("confirmation_mail_next_attempt_at")).isNotNull();
        assertThat(emails.settledMessagesTo(email)).isEmpty();

        // El transporte se recupera y el trabajo vencido se reintenta una sola vez.
        emails.failWith(null);
        jdbc.update("UPDATE contact_requests SET team_mail_next_attempt_at = ?, confirmation_mail_next_attempt_at = ? "
                        + "WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))),
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), id);
        mailService.retryDue();
        mailService.retryDue();

        awaitMailStatus(id, "SENT");
        Map<String, Object> recovered = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", id);
        assertThat(recovered.get("confirmation_mail_attempts")).isEqualTo(2);
        assertThat(recovered.get("confirmation_mail_error_code")).isNull();
        assertThat(emails.settledMessagesTo(email)).hasSize(1);
    }

    @Test
    void retriesStopAtTheAttemptLimit() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        emails.failWith(new EmailDeliveryException(EmailDeliveryException.TRANSPORT_ERROR));
        UUID id = requestId(send(ContactRequestPayloads.valid(email, "Negocio limite"), null)
                .andExpect(status().isAccepted()).andReturn());
        awaitMailStatus(id, "FAILED");

        for (int attempt = 0; attempt < 8; attempt++) {
            jdbc.update("UPDATE contact_requests SET team_mail_next_attempt_at = now() - interval '1 minute', "
                    + "confirmation_mail_next_attempt_at = now() - interval '1 minute' WHERE id = ?", id);
            mailService.retryDue();
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", id);
        assertThat(row.get("confirmation_mail_attempts")).isEqualTo(5);
        assertThat(row.get("team_mail_attempts")).isEqualTo(5);
        assertThat(row.get("status")).isEqualTo("RECEIVED");
    }

    // ------------------------------------------------------------ privacidad

    @Test
    void personalDataNeverReachesTheLogsEvenWhenSendingFails(CapturedOutput output) throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        emails.failWith(new EmailDeliveryException(EmailDeliveryException.TRANSPORT_ERROR));
        Map<String, Object> body = ContactRequestPayloads.valid(email, "Negocio Confidencial");
        body.put("fullName", "Nombre Sensible Único");
        body.put("message", "Comentario muy privado");

        UUID id = requestId(send(body, null).andExpect(status().isAccepted()).andReturn());
        awaitMailStatus(id, "FAILED");

        assertThat(output.getAll())
                .doesNotContain(email, "Nombre Sensible Único", "Negocio Confidencial", "Comentario muy privado",
                        "5555-1234");
    }

    @Test
    void retentionAnonymizesPersonalDataButTheKeyStillReturnsTheSameRequestId() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();
        UUID key = UUID.randomUUID();
        UUID id = requestId(send(ContactRequestPayloads.valid(email, "Negocio retencion"), key)
                .andExpect(status().isAccepted()).andReturn());
        jdbc.update("UPDATE contact_requests SET retention_until = now() - interval '1 day' WHERE id = ?", id);

        int anonymized = purgeJob.purge(Instant.now());

        assertThat(anonymized).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM contact_requests WHERE id = ?", id);
        assertThat(row.get("email")).isEqualTo("[anonimizado]");
        assertThat(row.get("full_name")).isEqualTo("[anonimizado]");
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("message")).isNull();
        assertThat(row.get("ip_hash")).isNull();
        assertThat(row.get("dedupe_key")).isNull();
        assertThat(row.get("anonymized_at")).isNotNull();
        UUID replay = requestId(send(ContactRequestPayloads.valid(email, "Negocio retencion"), key)
                .andExpect(status().isAccepted()).andReturn());
        assertThat(replay).isEqualTo(id);
    }

    // ------------------------------------------------------------ utilidades

    private org.springframework.test.web.servlet.ResultActions send(Map<String, Object> body, Object key)
            throws Exception {
        MockHttpServletRequestBuilder request = post(ContactRequestPayloads.PATH)
                .contentType(APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return mockMvc.perform(request);
    }

    private UUID requestId(MvcResult result) throws Exception {
        return UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /** Espera (sin dormir fijo largo) a que ambos correos de la solicitud dejen de estar PENDING y tengan {@code status}. */
    private void awaitMailStatus(UUID id, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT team_mail_status, confirmation_mail_status FROM contact_requests WHERE id = ?", id);
            if (expected.equals(row.get("team_mail_status")) && expected.equals(row.get("confirmation_mail_status"))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Los correos de " + id + " no llegaron a " + expected);
    }
}
