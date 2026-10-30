package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.RegisterCustomerRequest;
import com.omniretail.backend.auth.dto.RegisterCustomerResponse;
import com.omniretail.backend.auth.dto.VerifyEmailResponse;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmailVerification;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import com.omniretail.backend.auth.repository.EmailVerificationRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.config.FrontendProperties;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.notification.EmailLayout;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import jakarta.validation.Validator;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro publico de clientes y verificacion de su correo (mismas reglas que MockAuthRepository).
 *
 * <p>Anti-enumeracion: el registro responde exactamente lo mismo exista o no el correo en la tienda. Con un
 * correo nuevo crea la cuenta pendiente y envia el enlace; con el de una cuenta de cliente ya verificada
 * envia al buzon el aviso "ya tienes una cuenta" (como mucho {@value #ACCOUNT_EXISTS_NOTICE_LIMIT} cada 30
 * minutos); con el de una cuenta pendiente cuyo enlace ya vencio, reemplaza ese registro pendiente. En
 * cualquier otro caso (empleado, cuenta deshabilitada, pendiente con enlace vigente, mismo correo con otras
 * mayusculas) no hace nada. La contrasena se hashea siempre, para que el tiempo de respuesta no delate el caso.
 *
 * <p>Una cuenta pendiente nunca queda "secuestrada": se puede pedir otro enlace ({@link #resendVerification}) y,
 * vencido el enlace, volver a registrarse.
 */
@Service
@RequiredArgsConstructor
public class CustomerRegistrationService {

    static final Duration EMAIL_VERIFICATION_TTL = Duration.ofMinutes(30);
    static final int ACCOUNT_EXISTS_NOTICE_LIMIT = 3;
    static final Duration ACCOUNT_EXISTS_NOTICE_WINDOW = Duration.ofMinutes(30);
    /** Mismo limite que la recuperacion de contrasena: 3 enlaces por cuenta cada 30 minutos. */
    static final int VERIFICATION_RESEND_LIMIT = 3;
    static final Duration VERIFICATION_RESEND_WINDOW = Duration.ofMinutes(30);
    static final String REGISTRATION_FAILED_MESSAGE = "No se pudo completar el registro.";
    static final String INVALID_TOKEN_MESSAGE = "Este enlace no es válido o ya expiró.";

    /** Mismo tope que TEXT_FIELD_POLICY.NAME_MAX_LENGTH del frontend (perfil y registro). */
    static final int NAME_MAX_LENGTH = 100;
    /**
     * Misma regla que PERSON_NAME_PATTERN (person-name-policy.ts del frontend): letras Unicode con sus
     * marcas, separadas por un unico espacio, apostrofo o guion. Deja fuera HTML, digitos, simbolos,
     * caracteres de control e invisibles (ancho cero, overrides bidi) y saltos de linea.
     */
    static final Pattern PERSON_NAME = Pattern.compile("^\\p{L}[\\p{L}\\p{M}]*(?:[ '-][\\p{L}\\p{M}]+)*$");
    static final String NAME_INVALID_MESSAGE =
            "El nombre solo puede contener letras, espacios, apóstrofos y guiones.";
    private static final Pattern TYPOGRAPHIC_APOSTROPHES = Pattern.compile("[\u2019\u2018\u00B4`]");
    private static final Pattern TYPOGRAPHIC_DASHES = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014]");
    private static final Pattern REPEATED_SPACES = Pattern.compile(" {2,}");
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final int PHONE_DIGITS = 8;
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Set<AccountStatus> ACCOUNT_EXISTS_NOTICE_STATUSES =
            Set.of(AccountStatus.active, AccountStatus.temporarily_locked);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AuthAccountRepository authAccountRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final AuthAuditLogRepository auditLogRepository;
    private final CustomerRepository customerRepository;
    private final AuthAuditService auditService;
    private final CustomerAccountFactory customerAccountFactory;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final FrontendProperties frontendProperties;
    private final Validator validator;

    /**
     * Registro concurrente con el mismo correo, detectado por la BD al crear la cuenta. Deshace la
     * transaccion (la sesion de Hibernate queda inservible tras el fallo del flush); el controlador
     * responde lo mismo que siempre.
     */
    public static final class ConcurrentRegistrationException extends RuntimeException {

        private final transient RegisterCustomerResponse response;

        ConcurrentRegistrationException(RegisterCustomerResponse response) {
            super(null, null, false, false);
            this.response = response;
        }

        public RegisterCustomerResponse response() {
            return response;
        }
    }

    /**
     * Crea en una sola transaccion el cliente, su usuario, su cuenta (pendiente de verificacion) y la
     * verificacion de correo, envia el aviso "ya tienes una cuenta" o reemplaza un registro pendiente vencido.
     * Los correos salen despues del commit.
     * Siempre devuelve la misma respuesta.
     *
     * @throws ConcurrentRegistrationException si otro registro con el mismo correo gano la carrera.
     */
    @Transactional
    public RegisterCustomerResponse register(String slug, RegisterCustomerRequest request) {
        validate(request);
        String name = normalizeName(request.name());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();
        RegisterCustomerResponse accepted = RegisterCustomerResponse.accepted(name, email);

        Tenant tenant = tenantRepository.findBySlug(slug)
                .filter(found -> found.getStatus() == TenantStatus.active)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "REGISTRATION_UNAVAILABLE", REGISTRATION_FAILED_MESSAGE));
        UUID tenantId = tenant.getId();
        // Antes de mirar si el correo existe: el BCrypt domina el tiempo de respuesta en todos los casos.
        String passwordHash = passwordEncoder.encode(request.password());
        Instant now = Instant.now();

        // R-A03: correo unico dentro del tenant, sea cual sea el tipo de cuenta que ya lo usa.
        Optional<User> existing = userRepository.findByTenantIdAndEmail(tenantId, email);
        if (existing.isPresent()) {
            handleExistingUser(existing.get(), tenant, new Registration(name, phone, passwordHash), now);
            return accepted;
        }
        if (userRepository.existsByTenantIdAndEmailIgnoreCase(tenantId, email)) {
            return accepted; // Mismo correo con otras mayusculas: no se adivina a que cuenta corresponde.
        }

        User user;
        try {
            user = customerAccountFactory.create(tenantId, name, email, phone, passwordHash,
                    AccountStatus.pending_verification).user();
        } catch (CustomerAccountFactory.EmailTakenException ex) {
            throw new ConcurrentRegistrationException(accepted);
        }
        issueVerification(user, tenant, now);
        return accepted;
    }

    /**
     * Consume un token vigente y activa la cuenta solo si estaba pendiente de verificacion: una cuenta
     * deshabilitada o archivada nunca se reactiva por este medio. Token inexistente, usado o vencido
     * producen el mismo error.
     */
    @Transactional
    public VerifyEmailResponse verifyEmail(String token) {
        Instant now = Instant.now();
        EmailVerification verification = emailVerificationRepository.findByTokenHashForUpdate(AuthTokens.hash(token))
                .filter(found -> found.isUsable(now))
                .orElseThrow(CustomerRegistrationService::invalidToken);
        User user = userRepository.findById(verification.getUserId()).orElseThrow(CustomerRegistrationService::invalidToken);

        verification.setVerifiedAt(now);
        authAccountRepository.findByUserId(user.getId())
                .filter(account -> account.getStatus() == AccountStatus.pending_verification)
                .ifPresent(account -> account.setStatus(AccountStatus.active));

        String tenantSlug = tenantRepository.findById(user.getTenantId()).map(Tenant::getSlug).orElse(null);
        return new VerifyEmailResponse(tenantSlug);
    }

    /**
     * Envia un enlace de verificacion nuevo a una cuenta de cliente pendiente de la tienda e invalida los
     * anteriores. Cuenta inexistente o ya verificada, empleado, tienda inactiva o limite alcanzado (3 enlaces
     * cada 30 minutos por cuenta, contando el del registro): no hace nada. No devuelve nada: el llamador
     * responde siempre lo mismo.
     */
    @Transactional
    public void resendVerification(String email, String tenantSlug) {
        Instant now = Instant.now();
        Optional<Tenant> tenant = tenantRepository.findBySlug(tenantSlug)
                .filter(found -> found.getStatus() == TenantStatus.active);
        if (tenant.isEmpty()) {
            return;
        }
        Optional<User> user = userRepository.findByTenantIdAndEmail(tenant.get().getId(), email)
                .filter(found -> found.getType() == UserType.customer && found.getStatus() == UserStatus.active);
        if (user.isEmpty()) {
            return;
        }
        // Bloquea la cuenta: dos reenvios simultaneos no pueden saltarse el limite.
        Optional<AuthAccount> account = authAccountRepository.findByUserIdForUpdate(user.get().getId())
                .filter(found -> found.getStatus() == AccountStatus.pending_verification);
        if (account.isEmpty() || emailVerificationRepository.countByUserIdAndCreatedAtAfter(
                user.get().getId(), now.minus(VERIFICATION_RESEND_WINDOW)) >= VERIFICATION_RESEND_LIMIT) {
            return;
        }
        supersedeOpenVerifications(user.get().getId(), now);
        issueVerification(user.get(), tenant.get(), now);
    }

    /** Datos (ya validados y normalizados) de un intento de registro. */
    private record Registration(String name, String phone, String passwordHash) {
    }

    /**
     * El correo ya existe en la tienda. Un cliente activo con la cuenta verificada (activa o bloqueada
     * temporalmente) recibe el aviso; una cuenta pendiente con el enlace vencido se reemplaza; empleados y
     * cuentas deshabilitadas o archivadas, nada.
     */
    private void handleExistingUser(User user, Tenant tenant, Registration registration, Instant now) {
        if (user.getType() != UserType.customer || user.getStatus() != UserStatus.active) {
            return;
        }
        // Bloquea la cuenta: dos registros simultaneos no pueden saltarse el limite de avisos ni pisarse.
        Optional<AuthAccount> account = authAccountRepository.findByUserIdForUpdate(user.getId());
        if (account.isEmpty()) {
            return;
        }
        if (account.get().getStatus() == AccountStatus.pending_verification) {
            replaceExpiredPending(user, account.get(), tenant, registration, now);
        } else if (ACCOUNT_EXISTS_NOTICE_STATUSES.contains(account.get().getStatus())) {
            sendAccountExistsNotice(user, account.get(), tenant, now);
        }
    }

    /**
     * Registro pendiente cuyo enlace ya vencio: nadie probo ser dueno del correo, asi que el nuevo registro lo
     * reemplaza (nombre, telefono, contrasena y enlace nuevo; el codigo de cliente se conserva). Con un enlace
     * aun vigente no se toca nada: para eso esta el reenvio.
     */
    private void replaceExpiredPending(User user, AuthAccount account, Tenant tenant, Registration registration,
            Instant now) {
        List<EmailVerification> open =
                emailVerificationRepository.findByUserIdAndVerifiedAtIsNullAndSupersededAtIsNull(user.getId());
        if (open.stream().anyMatch(verification -> verification.isUsable(now))) {
            return;
        }
        open.forEach(verification -> verification.setSupersededAt(now));
        user.setName(registration.name());
        user.setPhone(registration.phone());
        if (user.getCustomerId() != null) {
            customerRepository.findByTenantIdAndId(tenant.getId(), user.getCustomerId()).ifPresent(customer -> {
                customer.setName(registration.name());
                customer.setPhone(registration.phone());
            });
        }
        account.setPasswordHash(registration.passwordHash());
        issueVerification(user, tenant, now);
    }

    /** Un enlace nuevo invalida los anteriores que siguieran sin usar. */
    private void supersedeOpenVerifications(UUID userId, Instant now) {
        emailVerificationRepository.findByUserIdAndVerifiedAtIsNullAndSupersededAtIsNull(userId)
                .forEach(verification -> verification.setSupersededAt(now));
    }

    private void sendAccountExistsNotice(User user, AuthAccount account, Tenant tenant, Instant now) {
        int recentNotices = auditLogRepository.findRecentEvents(account.getId(), now.minus(ACCOUNT_EXISTS_NOTICE_WINDOW),
                Set.of(AuthAuditService.REGISTRATION_EXISTING_ACCOUNT)).size();
        if (recentNotices >= ACCOUNT_EXISTS_NOTICE_LIMIT) {
            return; // Limite alcanzado: se omite en silencio, como la recuperacion.
        }
        auditService.record(user.getTenantId(), user.getId(), account.getId(),
                AuthAuditService.REGISTRATION_EXISTING_ACCOUNT, null);
        eventPublisher.publishEvent(new EmailRequestedEvent(accountExistsEmail(user, tenant, account.getEmail())));
    }

    /** Nuevo enlace de verificacion (30 min), guardado solo como hash; el correo sale despues del commit. */
    private void issueVerification(User user, Tenant tenant, Instant now) {
        String token = AuthTokens.generate();
        emailVerificationRepository.save(EmailVerification.builder()
                .userId(user.getId())
                .tokenHash(AuthTokens.hash(token))
                .createdAt(now)
                .expiresAt(now.plus(EMAIL_VERIFICATION_TTL))
                .build());
        eventPublisher.publishEvent(new EmailRequestedEvent(verificationEmail(user, tenant.getName(), token)));
    }

    /**
     * Forma canonica del nombre antes de validar y guardar, igual que normalizePersonName del frontend:
     * NFC, apostrofos y guiones tipograficos a ASCII, sin espacios en los extremos y sin espacios
     * repetidos. No quita caracteres no permitidos: un nombre invalido se rechaza, no se recorta.
     */
    static String normalizeName(String raw) {
        if (raw == null) return "";
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC);
        name = TYPOGRAPHIC_APOSTROPHES.matcher(name).replaceAll("'");
        name = TYPOGRAPHIC_DASHES.matcher(name).replaceAll("-");
        return REPEATED_SPACES.matcher(name.trim()).replaceAll(" ");
    }

    /** Mismas reglas y mensajes que register.validation.ts; devuelve todos los campos con error a la vez. */
    private void validate(RegisterCustomerRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = normalizeName(request.name());
        if (name.isEmpty()) {
            errors.put("name", "El nombre es obligatorio.");
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.put("name", "El nombre no puede superar " + NAME_MAX_LENGTH + " caracteres.");
        } else if (!PERSON_NAME.matcher(name).matches()) {
            errors.put("name", NAME_INVALID_MESSAGE);
        }

        String email = request.email() == null ? "" : request.email().trim();
        if (email.isEmpty()) {
            errors.put("email", "El correo es obligatorio.");
        } else if (email.length() > EMAIL_MAX_LENGTH) {
            errors.put("email", "El correo no puede superar " + EMAIL_MAX_LENGTH + " caracteres.");
        } else if (!validator.validateValue(RegisterCustomerRequest.class, "email", email).isEmpty()
                // La entidad aplica un @Email mas estricto que el patron del formulario: sin esta
                // comprobacion, un correo como a<b@c.d pasaba aqui y fallaba al guardar (500).
                || !validator.validateValue(User.class, "email", email.toLowerCase(Locale.ROOT)).isEmpty()) {
            errors.put("email", "Ingrese un correo con formato válido.");
        }

        // Opcional: solo se valida si se escribio algo (contact-policy.ts).
        String phone = request.phone() == null ? "" : request.phone().trim();
        if (!phone.isEmpty()) {
            if (!DIGITS.matcher(phone).matches()) {
                errors.put("phone", "El teléfono solo puede contener números.");
            } else if (phone.length() != PHONE_DIGITS) {
                errors.put("phone", "El teléfono debe tener exactamente " + PHONE_DIGITS + " dígitos.");
            }
        }

        PasswordPolicy.CUSTOMER.validate(request.password(), request.email())
                .ifPresent(message -> errors.put("password", message));

        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private EmailMessage verificationEmail(User user, String businessName, String token) {
        String link = frontendProperties.link("/verificar-correo/" + token);
        long minutes = EMAIL_VERIFICATION_TTL.toMinutes();
        String body = """
                Hola %s:

                Gracias por registrarte. Para activar tu cuenta, verifica tu correo con este enlace:

                %s

                El enlace vence en %d minutos. Si no creaste esta cuenta, ignora este mensaje.
                """.formatted(user.getName(), link, minutes);
        String html = EmailLayout.render(businessName, "Verifica tu correo", List.of(
                EmailLayout.paragraph("Hola ", user.getName(), ":"),
                EmailLayout.paragraph(
                        "Gracias por registrarte. Para activar tu cuenta, verifica tu correo con este enlace:"),
                EmailLayout.button("Verificar mi correo", link),
                EmailLayout.notice("El enlace vence en %d minutos. Si no creaste esta cuenta, ignora este mensaje."
                        .formatted(minutes))));
        return EmailMessage.html(user.getTenantId(), EmailPurpose.EMAIL_VERIFICATION, user.getEmail(),
                "Verifica tu correo", body, html);
    }

    /** Sin enlaces con token: solo lleva a iniciar sesion; "Olvide mi contrasena" esta en esa misma pantalla. */
    private EmailMessage accountExistsEmail(User user, Tenant tenant, String to) {
        String link = frontendProperties.link("/tienda/" + tenant.getSlug() + "/iniciar-sesion");
        String body = """
                Hola %s:

                Alguien intentó crear una cuenta en %s con este correo, pero ya tienes una.

                Inicia sesión aquí: %s

                Si no recuerdas tu contraseña, usa "¿Olvidaste tu contraseña?" en esa misma pantalla. Si no fuiste
                tú, ignora este mensaje: tu cuenta sigue igual.
                """.formatted(user.getName(), tenant.getName(), link);
        String html = EmailLayout.render(tenant.getName(), "Ya tienes una cuenta", List.of(
                EmailLayout.paragraph("Hola ", user.getName(), ":"),
                EmailLayout.paragraph("Alguien intentó crear una cuenta en ", tenant.getName(),
                        " con este correo, pero ya tienes una."),
                EmailLayout.button("Iniciar sesión", link),
                EmailLayout.notice("Si no recuerdas tu contraseña, usa \"¿Olvidaste tu contraseña?\" en esa misma "
                        + "pantalla. Si no fuiste tú, ignora este mensaje: tu cuenta sigue igual.")));
        return EmailMessage.html(user.getTenantId(), EmailPurpose.ACCOUNT_ALREADY_EXISTS, to,
                "Ya tienes una cuenta", body, html);
    }

    private static BusinessException invalidToken() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MESSAGE);
    }
}
