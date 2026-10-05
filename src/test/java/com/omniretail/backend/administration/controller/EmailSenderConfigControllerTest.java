package com.omniretail.backend.administration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AuthAuditLog;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.shared.notification.EmailCredentialCrypto;
import com.omniretail.backend.shared.notification.EmailDeliveryException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.TenantEmailSender;
import com.omniretail.backend.shared.notification.TenantEmailSenderConfig;
import com.omniretail.backend.shared.notification.TenantEmailSenderConfigRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EmailSenderConfigControllerTest {

    private static final String URL = "/api/v1/administration/email-sender";
    private static final String PASSWORD = "abcdEFGH12345678";
    private static final List<String> MANAGE = List.of("admin.email_config.manage");
    private static final List<String> READ = List.of("admin.email_config.read");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private TenantEmailSenderConfigRepository configRepository;
    @Autowired private AuthAuditLogRepository auditRepository;
    @Autowired private EmailCredentialCrypto crypto;

    @MockitoBean private TenantEmailSender tenantEmailSender;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        String token = tokenFor(persistTenant(), List.of());

        mockMvc.perform(get(URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("tienda@gmail.com", "Mi Tienda", PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/test").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"a@b.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(URL).header("Authorization", bearer(token))).andExpect(status().isForbidden());
    }

    @Test
    void readOnlyPermissionCanGetButNotChange() throws Exception {
        String token = tokenFor(persistTenant(), READ);

        mockMvc.perform(get(URL).header("Authorization", bearer(token))).andExpect(status().isOk());
        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("tienda@gmail.com", "Mi Tienda", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getWithoutConfigReturnsNotConfigured() throws Exception {
        String token = tokenFor(persistTenant(), MANAGE);

        mockMvc.perform(get(URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.status").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.provider").value("GMAIL_SMTP"))
                .andExpect(jsonPath("$.senderEmail").value(nullValue()))
                .andExpect(jsonPath("$.lastVerifiedAt").value(nullValue()));
    }

    @Test
    void putCreatesConfigEncryptedAndNeverReturnsTheSecret() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);

        String response = mockMvc.perform(put(URL).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        // Google muestra la contraseña en grupos de 4: se normaliza quitando espacios.
                        .content(body("Tienda@Gmail.com", "Mi Tienda", "abcd EFGH 1234 5678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("GMAIL_SMTP"))
                .andExpect(jsonPath("$.senderEmail").value("tienda@gmail.com"))
                .andExpect(jsonPath("$.senderName").value("Mi Tienda"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.status").value("CONFIGURED"))
                .andExpect(jsonPath("$.lastVerifiedAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        String getResponse = mockMvc.perform(get(URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response + getResponse).doesNotContain(PASSWORD, "appPassword", "encrypted", "credential");
        TenantEmailSenderConfig stored = configRepository.findByTenantId(tenant.getId()).orElseThrow();
        assertThat(stored.getEncryptedCredential()).doesNotContain(PASSWORD);
        assertThat(crypto.decryptCredential(new EmailCredentialCrypto.Encrypted(
                stored.getEncryptedCredential(), stored.getCredentialIv(), stored.getEncryptionKeyVersion()),
                tenant.getId())).isEqualTo(PASSWORD);
        verify(tenantEmailSender).invalidate(tenant.getId());
        assertThat(auditActions(tenant)).containsExactly("email_sender.updated");
        assertThat(auditMetadata(tenant).toString()).doesNotContain(PASSWORD);
    }

    @Test
    void putWithoutPasswordOnCreateFails() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);

        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("tienda@gmail.com", "Mi Tienda", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_SENDER_INVALID"));
        assertThat(configRepository.findByTenantId(tenant.getId())).isEmpty();
    }

    @Test
    void putValidatesInput() throws Exception {
        String token = tokenFor(persistTenant(), MANAGE);

        for (String invalid : List.of(
                body("no-es-correo", "Mi Tienda", PASSWORD),
                body("tienda@gmail.com", "", PASSWORD),
                body("tienda@gmail.com", "x".repeat(101), PASSWORD),
                body("tienda@gmail.com", "Linea\r\nBcc: x@y.com", PASSWORD),
                body("tienda@gmail.com", "Mi Tienda", "corta"),
                body("tienda@gmail.com", "Mi Tienda", "abcdEFGH1234567!"),
                "{\"provider\":\"SENDGRID\",\"senderEmail\":\"tienda@gmail.com\",\"senderName\":\"X\",\"appPassword\":\""
                        + PASSWORD + "\"}")) {
            String response = mockMvc.perform(put(URL).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EMAIL_SENDER_INVALID"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(PASSWORD, "abcdEFGH1234567!");
        }
    }

    @Test
    void putOnlyNameKeepsCredentialAndStatus() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);
        saveConfig(token, "tienda@gmail.com", "Mi Tienda", PASSWORD);
        TenantEmailSenderConfig before = configRepository.findByTenantId(tenant.getId()).orElseThrow();

        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("tienda@gmail.com", "Nuevo Nombre", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderName").value("Nuevo Nombre"))
                .andExpect(jsonPath("$.status").value("CONFIGURED"));

        TenantEmailSenderConfig after = configRepository.findByTenantId(tenant.getId()).orElseThrow();
        assertThat(after.getEncryptedCredential()).isEqualTo(before.getEncryptedCredential());
        assertThat(after.getCredentialIv()).isEqualTo(before.getCredentialIv());
    }

    @Test
    void changingSenderEmailRequiresPasswordAndResetsVerification() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);
        saveConfig(token, "tienda@gmail.com", "Mi Tienda", PASSWORD);
        mockMvc.perform(post(URL + "/test").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"dueno@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("otra@gmail.com", "Mi Tienda", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_SENDER_INVALID"));
        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("otra@gmail.com", "Mi Tienda", "ZYXWvuts87654321")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderEmail").value("otra@gmail.com"))
                .andExpect(jsonPath("$.status").value("CONFIGURED"))
                .andExpect(jsonPath("$.lastVerifiedAt").value(nullValue()));
    }

    @Test
    void testSuccessMarksVerified() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);
        saveConfig(token, "tienda@gmail.com", "Mi Tienda", PASSWORD);

        mockMvc.perform(post(URL + "/test").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"dueno@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.lastVerifiedAt").isNotEmpty());

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(tenantEmailSender).sendTest(sent.capture());
        assertThat(sent.getValue().tenantId()).isEqualTo(tenant.getId());
        assertThat(sent.getValue().purpose()).isEqualTo(EmailPurpose.SENDER_TEST);
        assertThat(sent.getValue().to()).isEqualTo("dueno@example.com");
        assertThat(sent.getValue().subject()).isEqualTo("Prueba de configuración de correo - OmniRetail");
        assertThat(auditActions(tenant)).contains("email_sender.tested");
    }

    @Test
    void testAuthFailureMarksErrorWithBusinessErrorAndNoTrace() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);
        saveConfig(token, "tienda@gmail.com", "Mi Tienda", PASSWORD);
        willThrow(new EmailDeliveryException(EmailDeliveryException.AUTHENTICATION_FAILED))
                .given(tenantEmailSender).sendTest(any(EmailMessage.class));

        String response = mockMvc.perform(post(URL + "/test").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"dueno@example.com\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMAIL_SMTP_AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "No fue posible autenticar la cuenta de correo. Verifica el correo y la contraseña de aplicación."))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("javax", "jakarta", "Exception", "at com.", PASSWORD);
        // El estado ERROR y la auditoría sobreviven al error de negocio (sin rollback).
        TenantEmailSenderConfig stored = configRepository.findByTenantId(tenant.getId()).orElseThrow();
        assertThat(stored.getStatus().name()).isEqualTo("ERROR");
        assertThat(stored.getLastFailureAt()).isNotNull();
        mockMvc.perform(get(URL).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastFailureAt").isNotEmpty());
        assertThat(auditActions(tenant)).contains("email_sender.tested");
        assertThat(auditMetadata(tenant).toString()).doesNotContain(PASSWORD);
    }

    @Test
    void testWithoutConfigReturnsNotConfigured() throws Exception {
        String token = tokenFor(persistTenant(), MANAGE);

        mockMvc.perform(post(URL + "/test").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"dueno@example.com\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("EMAIL_SENDER_NOT_CONFIGURED"));
        verify(tenantEmailSender, never()).sendTest(any(EmailMessage.class));
    }

    @Test
    void testRejectsInvalidRecipient() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);
        saveConfig(token, "tienda@gmail.com", "Mi Tienda", PASSWORD);

        mockMvc.perform(post(URL + "/test").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_SENDER_INVALID"));
    }

    @Test
    void deleteRemovesCredentialAndReturnsToNotConfigured() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, MANAGE);
        saveConfig(token, "tienda@gmail.com", "Mi Tienda", PASSWORD);

        mockMvc.perform(delete(URL).header("Authorization", bearer(token))).andExpect(status().isNoContent());

        assertThat(configRepository.findByTenantId(tenant.getId())).isEmpty();
        verify(tenantEmailSender, org.mockito.Mockito.atLeastOnce()).invalidate(tenant.getId());
        mockMvc.perform(get(URL).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.status").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.configured").value(false));
        assertThat(auditActions(tenant)).contains("email_sender.disconnected");
        // Idempotente.
        mockMvc.perform(delete(URL).header("Authorization", bearer(token))).andExpect(status().isNoContent());
    }

    @Test
    void tenantAlwaysComesFromTheToken() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA, MANAGE);
        String tokenB = tokenFor(tenantB, MANAGE);

        saveConfig(tokenA, "a@gmail.com", "Tienda A", PASSWORD);

        mockMvc.perform(get(URL).header("Authorization", bearer(tokenB))).andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(get(URL).header("Authorization", bearer(tokenA)))
                .andExpect(jsonPath("$.senderEmail").value("a@gmail.com"));
        // Un tenantId en el body se ignora: no existe en el contrato.
        mockMvc.perform(put(URL).header("Authorization", bearer(tokenB)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenantA.getId() + "\",\"provider\":\"GMAIL_SMTP\","
                                + "\"senderEmail\":\"b@gmail.com\",\"senderName\":\"Tienda B\",\"appPassword\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        assertThat(configRepository.findByTenantId(tenantA.getId()).orElseThrow().getSenderEmail())
                .isEqualTo("a@gmail.com");
        assertThat(configRepository.findByTenantId(tenantB.getId()).orElseThrow().getSenderEmail())
                .isEqualTo("b@gmail.com");
        // Borrar con el token de B no toca la cuenta de A.
        mockMvc.perform(delete(URL).header("Authorization", bearer(tokenB))).andExpect(status().isNoContent());
        assertThat(configRepository.findByTenantId(tenantA.getId())).isPresent();
    }

    private void saveConfig(String token, String email, String name, String password) throws Exception {
        mockMvc.perform(put(URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, name, password)))
                .andExpect(status().isOk());
    }

    private static String body(String email, String name, String password) {
        return "{\"provider\":\"GMAIL_SMTP\",\"senderEmail\":\"" + email + "\",\"senderName\":\""
                + name.replace("\r", "\\r").replace("\n", "\\n") + "\""
                + (password == null ? "" : ",\"appPassword\":\"" + password + "\"") + "}";
    }

    private List<String> auditActions(Tenant tenant) {
        return audits(tenant).stream().map(AuthAuditLog::getAction).toList();
    }

    private List<Object> auditMetadata(Tenant tenant) {
        return audits(tenant).stream().map(log -> (Object) log.getMetadata()).toList();
    }

    private List<AuthAuditLog> audits(Tenant tenant) {
        return auditRepository.findAll().stream()
                .filter(log -> tenant.getId().equals(log.getTenantId()))
                .filter(log -> log.getAction().startsWith("email_sender."))
                .sorted(java.util.Comparator.comparing(AuthAuditLog::getCreatedAt))
                .toList();
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role actorRole = Role.builder()
                .name("Rol Actor " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        actorRole.setTenantId(tenant.getId());
        actorRole = roleRepository.save(actorRole);

        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        Session session = Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        session = sessionRepository.save(session);
        return jwtService.generateToken(user, session);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
