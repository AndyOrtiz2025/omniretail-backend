package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.RegisterCustomerRequest;
import com.omniretail.backend.auth.dto.RegisterCustomerResponse;
import com.omniretail.backend.auth.dto.VerifyEmailResponse;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmailVerification;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.EmailVerificationRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.shared.config.FrontendProperties;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import jakarta.validation.Validator;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registro publico de clientes y verificacion de su correo (mismas reglas que MockAuthRepository). */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRegistrationService {

    static final Duration EMAIL_VERIFICATION_TTL = Duration.ofMinutes(30);
    static final String REGISTRATION_FAILED_MESSAGE = "No se pudo completar el registro.";
    static final String EMAIL_ALREADY_REGISTERED_MESSAGE =
            "Ya existe una cuenta con este correo. Inicia sesión o recupera tu contraseña.";
    static final String INVALID_TOKEN_MESSAGE = "Este enlace no es válido o ya expiró.";

    private static final int NAME_MAX_LENGTH = 200;
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final int PHONE_DIGITS = 8;
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final String USERS_TENANT_EMAIL_CONSTRAINT = "uk_users_tenant_email";
    private static final List<String> NON_CUSTOMER_PERMISSION_PREFIXES = List.of("admin.", "pos.", "inventory.");

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CustomerRepository customerRepository;
    private final AuthAccountRepository authAccountRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final DocumentCounterService documentCounterService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final FrontendProperties frontendProperties;
    private final Validator validator;

    /**
     * Crea en una sola transaccion el cliente, su usuario, su cuenta (pendiente de verificacion) y la
     * verificacion de correo. El correo con el enlace sale despues del commit.
     */
    @Transactional
    public RegisterCustomerResponse register(String slug, RegisterCustomerRequest request) {
        validate(request);
        String name = request.name().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();

        Tenant tenant = tenantRepository.findBySlug(slug)
                .filter(found -> found.getStatus() == TenantStatus.active)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "REGISTRATION_UNAVAILABLE", REGISTRATION_FAILED_MESSAGE));
        UUID tenantId = tenant.getId();

        // R-A03: correo unico dentro del tenant, sea cual sea el tipo de cuenta que ya lo usa.
        if (userRepository.existsByTenantIdAndEmailIgnoreCase(tenantId, email)) {
            throw emailAlreadyRegistered();
        }
        Role customerRole = findCustomerRole(tenantId);

        // El contador hace flush: se pide antes de crear las entidades.
        String code = documentCounterService.nextCustomerCode(tenantId);
        // customers.user_id tiene FK a users y no se puede actualizar: primero el usuario, luego el cliente.
        User newUser = User.builder()
                .name(name)
                .email(email)
                .phone(phone)
                .type(UserType.customer)
                .status(UserStatus.active)
                .roleId(customerRole.getId())
                .build();
        newUser.setTenantId(tenantId);
        User user = userRepository.save(newUser);
        Customer newCustomer = Customer.builder()
                .userId(user.getId())
                .code(code)
                .name(name)
                .email(email)
                .phone(phone)
                .status(CustomerStatus.active)
                .build();
        newCustomer.setTenantId(tenantId);
        Customer customer = customerRepository.save(newCustomer);
        user.setCustomerId(customer.getId());
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(AccountStatus.pending_verification)
                .build());

        Instant now = Instant.now();
        String token = AuthTokens.generate();
        emailVerificationRepository.save(EmailVerification.builder()
                .userId(user.getId())
                .tokenHash(AuthTokens.hash(token))
                .createdAt(now)
                .expiresAt(now.plus(EMAIL_VERIFICATION_TTL))
                .build());
        flushDetectingDuplicateEmail();

        eventPublisher.publishEvent(new EmailRequestedEvent(verificationEmail(user, token)));
        return new RegisterCustomerResponse(RegisterCustomerResponse.UserView.from(user));
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
                .filter(found -> found.getVerifiedAt() == null)
                .filter(found -> now.isBefore(found.getExpiresAt()))
                .orElseThrow(CustomerRegistrationService::invalidToken);
        User user = userRepository.findById(verification.getUserId()).orElseThrow(CustomerRegistrationService::invalidToken);

        verification.setVerifiedAt(now);
        authAccountRepository.findByUserId(user.getId())
                .filter(account -> account.getStatus() == AccountStatus.pending_verification)
                .ifPresent(account -> account.setStatus(AccountStatus.active));

        String tenantSlug = tenantRepository.findById(user.getTenantId()).map(Tenant::getSlug).orElse(null);
        return new VerifyEmailResponse(tenantSlug);
    }

    /** Mismas reglas y mensajes que register.validation.ts; devuelve todos los campos con error a la vez. */
    private void validate(RegisterCustomerRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            errors.put("name", "El nombre es obligatorio.");
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.put("name", "El nombre no puede superar " + NAME_MAX_LENGTH + " caracteres.");
        }

        String email = request.email() == null ? "" : request.email().trim();
        if (email.isEmpty()) {
            errors.put("email", "El correo es obligatorio.");
        } else if (email.length() > EMAIL_MAX_LENGTH) {
            errors.put("email", "El correo no puede superar " + EMAIL_MAX_LENGTH + " caracteres.");
        } else if (!validator.validateValue(RegisterCustomerRequest.class, "email", email).isEmpty()) {
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

    /** Rol de sistema de cliente, buscado igual que el mock: nunca un rol con permisos operativos. */
    private Role findCustomerRole(UUID tenantId) {
        return roleRepository.findByTenantId(tenantId).stream()
                .filter(role -> Boolean.TRUE.equals(role.getIsSystem()))
                .filter(role -> role.getPermissions().contains("customer.account.read"))
                .filter(role -> role.getPermissions().stream()
                        .noneMatch(permission -> NON_CUSTOMER_PERMISSION_PREFIXES.stream().anyMatch(permission::startsWith)))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("El tenant {} no tiene el rol de sistema de cliente; no se puede registrar clientes.", tenantId);
                    return new BusinessException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", REGISTRATION_FAILED_MESSAGE);
                });
    }

    /** Un registro concurrente con el mismo correo lo detecta la BD: se responde igual que el chequeo previo. */
    private void flushDetectingDuplicateEmail() {
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            String constraint = findCause(ex, ConstraintViolationException.class)
                    .map(ConstraintViolationException::getConstraintName)
                    .orElse(null);
            String sqlState = findCause(ex, SQLException.class).map(SQLException::getSQLState).orElse(null);
            if ("23505".equals(sqlState) && USERS_TENANT_EMAIL_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw emailAlreadyRegistered();
            }
            throw ex;
        }
    }

    private EmailMessage verificationEmail(User user, String token) {
        String link = frontendProperties.link("/verificar-correo/" + token);
        String body = """
                Hola %s:

                Gracias por registrarte. Para activar tu cuenta, verifica tu correo con este enlace:

                %s

                El enlace vence en %d minutos. Si no creaste esta cuenta, ignora este mensaje.
                """.formatted(user.getName(), link, EMAIL_VERIFICATION_TTL.toMinutes());
        return EmailMessage.text(
                user.getTenantId(), EmailPurpose.EMAIL_VERIFICATION, user.getEmail(), "Verifica tu correo", body);
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable ex, Class<T> type) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 20; depth++) {
            if (type.isInstance(cause)) {
                return Optional.of(type.cast(cause));
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }

    private static BusinessException emailAlreadyRegistered() {
        return BusinessException.conflict("EMAIL_ALREADY_REGISTERED", EMAIL_ALREADY_REGISTERED_MESSAGE);
    }

    private static BusinessException invalidToken() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MESSAGE);
    }
}
