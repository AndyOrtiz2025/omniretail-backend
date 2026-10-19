package com.omniretail.backend.commercial;

import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registro de solicitudes comerciales. No crea usuarios, tenants, suscripciones, pagos ni entornos: solo guarda la
 * solicitud y deja el trabajo de correo pendiente en la misma transaccion; el envio ocurre despues del commit
 * ({@link ContactRequestMailListener}).
 *
 * <p>Idempotencia y deduplicacion las resuelve PostgreSQL con {@code ON CONFLICT DO NOTHING} sobre las restricciones
 * unicas: dos solicitudes simultaneas con la misma identidad producen una sola fila y una sola programacion de
 * correos. La seccion que vuelve a comprobar identidad y cuotas antes de insertar se serializa en PostgreSQL con un
 * advisory lock transaccional: funciona entre instancias y cubre tambien el limite global.
 *
 * <p>Logs: solo requestId y estado; nunca nombre, correo, telefono, comentarios ni IP.
 */
@Slf4j
@Service
@ContactRequestsEnabled
public class ContactRequestService {

    private static final Pattern MESSAGE_CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]|\\p{Cf}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Duration QUOTA_WINDOW = Duration.ofHours(24);
    /** Clave estable de dos enteros (ASCII "MARJ"/"YMQT"); no comparte espacio con los locks bigint existentes. */
    private static final String LOCK_QUOTAS_SQL = "SELECT pg_advisory_xact_lock(1296126538, 1498239316)";

    private static final String INSERT_SQL = """
            INSERT INTO contact_requests (
                id, status, full_name, business_name, email, phone, industry, modules, message,
                consent_version, consent_at, idempotency_key, dedupe_key, dedupe_day, ip_hash,
                team_mail_status, team_mail_next_attempt_at,
                confirmation_mail_status, confirmation_mail_next_attempt_at,
                created_at, retention_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final ContactRequestRepository repository;
    private final ContactRequestProperties properties;
    private final CaptchaVerifier captcha;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transaction;

    public ContactRequestService(
            ContactRequestRepository repository,
            ContactRequestProperties properties,
            CaptchaVerifier captcha,
            JdbcTemplate jdbc,
            ApplicationEventPublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.captcha = captcha;
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Datos ya normalizados y validados contra las listas permitidas. */
    private record Normalized(
            String fullName,
            String businessName,
            String email,
            String phone,
            String industry,
            List<String> modules,
            String message,
            String businessKey) {
    }

    public ContactRequestAccepted submit(CreateContactRequest input, UUID idempotencyKey, String clientIp) {
        Normalized data = normalize(input);
        boolean spam = input.website() != null && !input.website().isBlank();
        Instant now = Instant.now();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String ipHash = hmac(clientIp == null ? "unknown" : clientIp);
        String dedupeKey = spam ? null : hmac(data.email() + "|" + data.businessKey());

        // Repeticiones: misma identidad -> mismo requestId, sin cuotas, sin captcha y sin correos nuevos.
        Optional<ContactRequest> repeated = findRepeated(idempotencyKey, dedupeKey, day);
        if (repeated.isPresent()) {
            logReplay(repeated.get().getId(), idempotencyKey);
            return ContactRequestAccepted.received(repeated.get().getId());
        }

        enforceQuotas(spam, data.email(), ipHash, now);
        if (!spam && !captcha.verify(input.captchaToken(), clientIp)) {
            throw FieldValidationException.of("captchaToken", "No se pudo verificar que eres una persona.");
        }
        return transaction.execute(status ->
                register(input, data, spam, idempotencyKey, dedupeKey, day, ipHash, now));
    }

    private ContactRequestAccepted register(
            CreateContactRequest input,
            Normalized data,
            boolean spam,
            UUID idempotencyKey,
            String dedupeKey,
            LocalDate day,
            String ipHash,
            Instant now) {
        // Una sola seccion critica protege IP, correo y volumen global en todas las instancias de la aplicacion.
        jdbc.execute(LOCK_QUOTAS_SQL);
        Optional<ContactRequest> repeated = findRepeated(idempotencyKey, dedupeKey, day);
        if (repeated.isPresent()) {
            logReplay(repeated.get().getId(), idempotencyKey);
            return ContactRequestAccepted.received(repeated.get().getId());
        }
        // La comprobacion exterior evita captcha inutil cuando el cupo ya esta lleno; esta es la comprobacion
        // autoritativa, protegida por el mismo lock transaccional que la insercion.
        enforceQuotas(spam, data.email(), ipHash, now);

        UUID id = UUID.randomUUID();
        Timestamp created = Timestamp.from(now);
        Timestamp firstMailAttempt = spam ? null : Timestamp.from(now.plus(properties.mailInitialDelay()));
        String mailStatus = spam ? ContactMailStatus.NOT_REQUIRED.name() : ContactMailStatus.PENDING.name();
        int retentionDays = spam ? properties.spamRetentionDays() : properties.retentionDays();
        int inserted = jdbc.update(
                INSERT_SQL,
                id,
                (spam ? ContactRequestStatus.SPAM : ContactRequestStatus.RECEIVED).name(),
                data.fullName(),
                data.businessName(),
                data.email(),
                data.phone(),
                data.industry(),
                String.join(",", data.modules()),
                data.message(),
                input.consentVersion().trim(),
                created,
                idempotencyKey,
                dedupeKey,
                dedupeKey == null ? null : Date.valueOf(day),
                ipHash,
                mailStatus,
                firstMailAttempt,
                mailStatus,
                firstMailAttempt,
                created,
                Timestamp.from(now.plus(Duration.ofDays(retentionDays))));
        if (inserted == 1) {
            if (!spam) {
                // Dentro de la transaccion: el listener envia solo despues del commit.
                publisher.publishEvent(new ContactRequestRegisteredEvent(id));
            }
            log.info("Solicitud comercial registrada requestId={} status={}", id, spam ? "SPAM" : "RECEIVED");
            return ContactRequestAccepted.received(id);
        }
        // Otra solicitud con la misma identidad gano la carrera: se devuelve la suya.
        return findRepeated(idempotencyKey, dedupeKey, day)
                .map(existing -> ContactRequestAccepted.received(existing.getId()))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "CONTACT_REQUESTS_UNAVAILABLE",
                        "No pudimos registrar tu solicitud. Intenta de nuevo en unos minutos."));
    }

    /** Un 202 de repeticion no es un registro nuevo ni programa correos; se deja rastro sin datos personales. */
    private static void logReplay(UUID existingId, UUID idempotencyKey) {
        log.info("Solicitud comercial repetida requestId={} withIdempotencyKey={} (sin registro ni correos nuevos)",
                existingId, idempotencyKey != null);
    }

    private Optional<ContactRequest> findRepeated(UUID idempotencyKey, String dedupeKey, LocalDate day) {
        if (idempotencyKey != null) {
            Optional<ContactRequest> byKey = repository.findByIdempotencyKey(idempotencyKey);
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        return dedupeKey == null ? Optional.empty() : repository.findByDedupeKeyAndDedupeDay(dedupeKey, day);
    }

    private void enforceQuotas(boolean spam, String email, String ipHash, Instant now) {
        Instant since = now.minus(QUOTA_WINDOW);
        if (repository.countByIpHashAndCreatedAtAfter(ipHash, since) >= properties.perIpPerDay()) {
            log.warn("Solicitud comercial rechazada por cuota quota=ip");
            throw quotaExceeded();
        }
        if (spam) {
            return;
        }
        if (repository.countByEmailAndCreatedAtAfter(email, since) >= properties.perEmailPerDay()) {
            log.warn("Solicitud comercial rechazada por cuota quota=email");
            throw quotaExceeded();
        }
        if (repository.countByStatusAndCreatedAtAfter(ContactRequestStatus.RECEIVED, since)
                >= properties.globalPerDay()) {
            log.warn("Solicitud comercial rechazada por cuota quota=global");
            // Corte de seguridad del SMTP compartido con los correos de seguridad; no se guarda nada.
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CONTACT_REQUESTS_UNAVAILABLE",
                    "Estamos recibiendo muchas solicitudes. Intenta de nuevo mas tarde.");
        }
    }

    private static BusinessException quotaExceeded() {
        return new BusinessException(
                HttpStatus.TOO_MANY_REQUESTS,
                "CONTACT_QUOTA_EXCEEDED",
                "Ya recibimos varias solicitudes desde tu conexion o correo. Intenta de nuevo mas tarde.");
    }

    private static Normalized normalize(CreateContactRequest input) {
        String industry = input.industry().trim().toLowerCase(Locale.ROOT);
        if (!ContactCatalog.INDUSTRIES.contains(industry)) {
            throw FieldValidationException.of("industry", "Rubro no permitido.");
        }
        Set<String> modules = new LinkedHashSet<>();
        for (String module : input.modules()) {
            String key = module.trim();
            if (!ContactCatalog.MODULES.contains(key) || !modules.add(key)) {
                throw FieldValidationException.of("modules", "Modulos no permitidos o repetidos.");
            }
        }
        // Se comprueba el texto original: String.trim() descarta tambien los caracteres de control de los extremos.
        if (input.message() != null && MESSAGE_CONTROL.matcher(input.message()).find()) {
            throw FieldValidationException.of("message", "Contiene caracteres no permitidos.");
        }
        String message = input.message() == null || input.message().isBlank() ? null : input.message().strip();
        String businessName = input.businessName().trim();
        return new Normalized(
                input.fullName().trim(),
                businessName,
                input.email().trim().toLowerCase(Locale.ROOT),
                input.phone().trim(),
                industry,
                List.copyOf(modules),
                message,
                WHITESPACE.matcher(businessName).replaceAll(" ").toLowerCase(Locale.ROOT));
    }

    /** HMAC-SHA256 hexadecimal con el secreto del servidor: sirve para cuotas y deduplicacion sin guardar la IP. */
    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.ipHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", ex);
        }
    }
}
