package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.EmailSenderResponse;
import com.omniretail.backend.administration.dto.SaveEmailSenderRequest;
import com.omniretail.backend.administration.dto.TestEmailSenderRequest;
import com.omniretail.backend.auth.service.AuthAuditService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.notification.EmailCredentialCrypto;
import com.omniretail.backend.shared.notification.EmailDeliveryException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailSenderProvider;
import com.omniretail.backend.shared.notification.EmailSenderStatus;
import com.omniretail.backend.shared.notification.TenantEmailSender;
import com.omniretail.backend.shared.notification.TenantEmailSenderConfig;
import com.omniretail.backend.shared.notification.TenantEmailSenderConfigRepository;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuracion del remitente Gmail del tenant. La contrasena de aplicacion es write-only: se cifra al
 * guardarla y no existe ningun camino que la devuelva, la registre o la meta en la auditoria.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class EmailSenderConfigService {

    public static final String ACTION_UPDATED = "email_sender.updated";
    public static final String ACTION_TESTED = "email_sender.tested";
    public static final String ACTION_DISCONNECTED = "email_sender.disconnected";

    private static final String INVALID = "EMAIL_SENDER_INVALID";
    private static final String AUTH_FAILED_MESSAGE =
            "No fue posible autenticar la cuenta de correo. Verifica el correo y la contraseña de aplicación.";
    private static final String TEST_SUBJECT = "Prueba de configuración de correo - OmniRetail";
    private static final String TEST_BODY = """
            Este es un correo de prueba de OmniRetail.

            Si lo recibiste, la cuenta configurada puede enviar los correos operativos de tu negocio \
            (pedidos, despachos, órdenes de compra y recepciones).
            """;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s<>\"]+@[^@\\s<>\"]+\\.[^@\\s<>\"]+$");
    private static final Pattern APP_PASSWORD = Pattern.compile("^[A-Za-z0-9]{16}$");
    private static final int EMAIL_MAX = 254;
    private static final int NAME_MAX = 100;

    private final TenantEmailSenderConfigRepository configRepository;
    private final EmailCredentialCrypto crypto;
    private final TenantEmailSender tenantEmailSender;
    private final AuthAuditService auditService;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public EmailSenderResponse getConfig() {
        UUID tenantId = currentUser.require().tenantId();
        return configRepository
                .findByTenantId(tenantId)
                .map(EmailSenderResponse::from)
                .orElseGet(EmailSenderResponse::notConfigured);
    }

    public EmailSenderResponse saveConfig(SaveEmailSenderRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        EmailSenderProvider provider = parseProvider(request.provider());
        String senderEmail = validEmail(request.senderEmail(), "El correo remitente");
        String senderName = validSenderName(request.senderName());
        String password = normalizePassword(request.appPassword());

        TenantEmailSenderConfig config = configRepository.findByTenantId(tenantId).orElse(null);
        boolean emailChanged = config == null || !config.getSenderEmail().equals(senderEmail);
        if (password == null && emailChanged) {
            throw invalid("La contraseña de aplicación es obligatoria al configurar o cambiar el correo remitente.");
        }
        if (config == null) {
            config = new TenantEmailSenderConfig();
            config.setTenantId(tenantId);
        }
        config.setProvider(provider);
        config.setSenderEmail(senderEmail);
        config.setSenderName(senderName);
        if (password != null) {
            EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(password, tenantId);
            config.setEncryptedCredential(encrypted.ciphertext());
            config.setCredentialIv(encrypted.iv());
            config.setEncryptionKeyVersion(encrypted.keyVersion());
            // Credencial nueva: no esta probada hasta que se ejecute /test.
            config.setStatus(EmailSenderStatus.CONFIGURED);
            config.setLastVerifiedAt(null);
            config.setLastFailureAt(null);
        }
        TenantEmailSenderConfig saved = configRepository.save(config);
        tenantEmailSender.invalidate(tenantId);

        auditService.record(tenantId, actor.userId(), null, ACTION_UPDATED,
                Map.of("provider", provider.name(), "senderEmailChanged", emailChanged,
                        "credentialChanged", password != null));
        return EmailSenderResponse.from(saved);
    }

    /**
     * No hace rollback ante el error de negocio: el estado ERROR y la auditoria del intento fallido deben
     * quedar guardados.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public EmailSenderResponse sendTest(TestEmailSenderRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        String recipient = validEmail(request == null ? null : request.recipient(), "El destinatario");
        TenantEmailSenderConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.CONFLICT,
                        "EMAIL_SENDER_NOT_CONFIGURED",
                        "Configura primero la cuenta de correo del negocio."));

        Instant now = Instant.now();
        try {
            tenantEmailSender.sendTest(EmailMessage.text(
                    tenantId, EmailPurpose.SENDER_TEST, recipient, TEST_SUBJECT, TEST_BODY));
        } catch (RuntimeException ex) {
            // Cualquier fallo (autenticacion, transporte, credencial ilegible) se reporta igual; nunca la excepcion.
            String reason = ex instanceof EmailDeliveryException delivery
                    ? delivery.getCode()
                    : EmailDeliveryException.TRANSPORT_ERROR;
            config.setStatus(EmailSenderStatus.ERROR);
            config.setLastFailureAt(now);
            configRepository.save(config);
            auditService.record(tenantId, actor.userId(), null, ACTION_TESTED,
                    Map.of("success", false, "reason", reason));
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_SMTP_AUTH_FAILED", AUTH_FAILED_MESSAGE);
        }
        config.setStatus(EmailSenderStatus.VERIFIED);
        config.setLastVerifiedAt(now);
        TenantEmailSenderConfig saved = configRepository.save(config);
        auditService.record(tenantId, actor.userId(), null, ACTION_TESTED, Map.of("success", true));
        return EmailSenderResponse.from(saved);
    }

    public void disconnect() {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        configRepository.findByTenantId(tenantId).ifPresent(configRepository::delete);
        tenantEmailSender.invalidate(tenantId);
        auditService.record(tenantId, actor.userId(), null, ACTION_DISCONNECTED, Map.of());
    }

    private static EmailSenderProvider parseProvider(String value) {
        if (value == null || !EmailSenderProvider.GMAIL_SMTP.name().equals(value.trim())) {
            throw invalid("El proveedor de correo no es compatible.");
        }
        return EmailSenderProvider.GMAIL_SMTP;
    }

    private static String validEmail(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > EMAIL_MAX || !EMAIL.matcher(normalized).matches()) {
            throw invalid(label + " no tiene un formato de correo válido.");
        }
        return normalized;
    }

    private static String validSenderName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > NAME_MAX) {
            throw invalid("El nombre del remitente es obligatorio y no puede superar " + NAME_MAX + " caracteres.");
        }
        // Evita inyeccion de cabeceras en el From.
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid("El nombre del remitente contiene caracteres no permitidos.");
        }
        return normalized;
    }

    /** Google muestra la contrasena en grupos de 4 separados por espacios: se quitan. Nunca se echa el valor en el error. */
    private static String normalizePassword(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", "");
        if (!APP_PASSWORD.matcher(normalized).matches()) {
            throw invalid("La contraseña de aplicación debe tener 16 caracteres alfanuméricos.");
        }
        return normalized;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, INVALID, message);
    }
}
