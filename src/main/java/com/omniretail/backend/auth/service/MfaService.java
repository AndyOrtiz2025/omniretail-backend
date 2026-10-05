package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.BeginMfaEnrollmentRequest;
import com.omniretail.backend.auth.dto.BeginMfaEnrollmentResponse;
import com.omniretail.backend.auth.dto.DisableMfaRequest;
import com.omniretail.backend.auth.dto.MfaRecoveryCodesResponse;
import com.omniretail.backend.auth.dto.MfaStatusResponse;
import com.omniretail.backend.auth.dto.VerifyMfaEnrollmentRequest;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.MfaEnrollment;
import com.omniretail.backend.auth.entity.MfaMethod;
import com.omniretail.backend.auth.entity.MfaRecoveryCode;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.MfaChallengeRepository;
import com.omniretail.backend.auth.repository.MfaEnrollmentRepository;
import com.omniretail.backend.auth.repository.MfaRecoveryCodeRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.validation.UnknownFields;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activacion, estado y desactivacion del MFA de la sesion actual (beginMfaEnrollment,
 * verifyMfaEnrollment, getMfaStatus y disableMfa de MockAuthRepository). Solo TOTP por ahora.
 *
 * <p>Las operaciones que cambian el MFA bloquean primero la cuenta (FOR UPDATE), asi se ejecutan de a una
 * por usuario. Con el MFA activo no se puede volver a iniciar la activacion (409): hay que desactivarlo,
 * y eso pide la contrasena; una sesion robada sola no alcanza para cambiar el segundo factor.
 */
@Service
@RequiredArgsConstructor
public class MfaService {

    /** RECOVERY_CODES_COUNT de mfa-policy.ts. */
    static final int RECOVERY_CODES_COUNT = 8;
    /** Intentos para confirmar la activacion antes de descartar el secreto pendiente. */
    static final int MAX_ENROLLMENT_ATTEMPTS = 5;
    static final String ISSUER = "OmniRetail";
    static final String WRONG_CURRENT_PASSWORD_MESSAGE = "La contraseña actual no es correcta.";
    static final String WRONG_MFA_CODE_MESSAGE = "El código de verificación en dos pasos no es correcto.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MfaEnrollmentRepository enrollmentRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaChallengeRepository challengeRepository;
    private final AuthAccountRepository authAccountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaCrypto mfaCrypto;
    private final MfaCodeVerifier codeVerifier;
    private final AuthAuditService auditService;
    private final Clock authClock;
    private final MfaEmailCodeSender emailCodeSender;

    @Transactional(readOnly = true)
    public MfaStatusResponse status(AuthenticatedUser actor) {
        return enrollmentRepository.findByUserId(actor.userId())
                .map(enrollment -> new MfaStatusResponse(
                        Boolean.TRUE.equals(enrollment.getEnabled()), enrollment.getMethod().name()))
                .orElse(new MfaStatusResponse(false, null));
    }

    /** Crea o reemplaza la activacion pendiente con un secreto nuevo. Solo aqui se devuelve el secreto. */
    @Transactional
    public BeginMfaEnrollmentResponse beginEnrollment(AuthenticatedUser actor, BeginMfaEnrollmentRequest request) {
        UnknownFields.reject(request.unknownFields());
        MfaMethod method = requireMethod(request.method());
        lockAccount(actor);
        User user = requireUser(actor);

        MfaEnrollment enrollment = enrollmentRepository.findByUserIdForUpdate(user.getId()).orElseGet(() -> {
            MfaEnrollment created = new MfaEnrollment();
            created.setUserId(user.getId());
            return created;
        });
        if (Boolean.TRUE.equals(enrollment.getEnabled())) {
            throw new BusinessException(HttpStatus.CONFLICT, "MFA_ALREADY_ENABLED",
                    "La verificación en dos pasos ya está activa. Desactívala primero para configurarla de nuevo.");
        }
        Instant now = authClock.instant();
        if (method == MfaMethod.email && !emailCodeSender.canSend(enrollment, now)) {
            throw MfaLoginService.resendLimited();
        }

        enrollment.setMethod(method);
        enrollment.setVerifiedAt(null);
        enrollment.setFailedAttempts(0);
        enrollment.setLastUsedStep(null);
        clearEmailCode(enrollment);
        if (method == MfaMethod.email) {
            // Repetir la activacion por correo hace de "reenviar": codigo nuevo, el anterior deja de servir.
            enrollment.setSecretCiphertext(null);
            String code = MfaEmailCodeSender.newCode();
            enrollment.setEmailCodeHash(mfaCrypto.hashEmailCode(user.getId(), code));
            enrollment.setEmailCodeExpiresAt(now.plus(MfaEmailCodeSender.CODE_TTL));
            emailCodeSender.send(enrollment, user, code, MfaEmailCodeSender.Purpose.ENROLLMENT, now);
            enrollmentRepository.save(enrollment);
            return new BeginMfaEnrollmentResponse(MfaMethod.email.name(), null, null);
        }

        byte[] secret = Totp.newSecret();
        enrollment.setSecretCiphertext(mfaCrypto.encryptSecret(secret, user.getId()));
        enrollmentRepository.save(enrollment);

        String base32 = Totp.base32Encode(secret);
        return new BeginMfaEnrollmentResponse(MfaMethod.totp.name(), base32, otpauthUri(user.getEmail(), base32));
    }

    /**
     * Envia por correo un codigo para el cambio de contrasena (solo con el metodo email: con la app el codigo
     * ya esta en el telefono). Vale 5 minutos, una vez; pedir otro invalida el anterior. Respeta la espera y
     * el tope de envios por usuario.
     */
    @Transactional
    public void sendActionCode(AuthenticatedUser actor) {
        lockAccount(actor);
        User user = requireUser(actor);
        MfaEnrollment enrollment = enrollmentRepository.findByUserIdForUpdate(user.getId())
                .filter(found -> Boolean.TRUE.equals(found.getEnabled()) && found.getMethod() == MfaMethod.email)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MFA_EMAIL_CODE_NOT_AVAILABLE",
                        "Tu verificación en dos pasos no usa códigos por correo."));
        Instant now = authClock.instant();
        if (!emailCodeSender.canSend(enrollment, now)) {
            throw MfaLoginService.resendLimited();
        }
        String code = MfaEmailCodeSender.newCode();
        enrollment.setEmailCodeHash(mfaCrypto.hashEmailCode(user.getId(), code));
        enrollment.setEmailCodeExpiresAt(now.plus(MfaEmailCodeSender.CODE_TTL));
        enrollment.setFailedAttempts(0);
        emailCodeSender.send(enrollment, user, code, MfaEmailCodeSender.Purpose.PASSWORD_CHANGE, now);
    }

    /**
     * Confirma la activacion con un codigo de la app y genera los 8 codigos de recuperacion (reemplaza
     * cualquier lote anterior). {@code noRollbackFor}: el intento fallido y su auditoria deben quedar.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public MfaRecoveryCodesResponse verifyEnrollment(AuthenticatedUser actor, VerifyMfaEnrollmentRequest request) {
        UnknownFields.reject(request.unknownFields());
        AuthAccount account = lockAccount(actor);
        User user = requireUser(actor);
        MfaEnrollment enrollment = enrollmentRepository.findByUserIdForUpdate(user.getId())
                .filter(found -> !Boolean.TRUE.equals(found.getEnabled()) && hasPendingCode(found))
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "MFA_ENROLLMENT_NOT_PENDING",
                        "No hay una verificación en dos pasos pendiente de confirmar."));

        Instant now = authClock.instant();
        boolean valid = enrollment.getMethod() == MfaMethod.email
                ? codeVerifier.verifyEmailCode(pendingEmailCode(enrollment), request.code(), now)
                : codeVerifier.verifyTotp(enrollment, request.code(), now);
        if (!valid) {
            int attempts = enrollment.getFailedAttempts() + 1;
            auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.MFA_FAILED,
                    Map.of("context", "enrollment", "attempt", attempts));
            if (attempts >= MAX_ENROLLMENT_ATTEMPTS) {
                // Se descarta el secreto o el codigo: hay que volver a empezar la activacion.
                enrollment.setSecretCiphertext(null);
                clearEmailCode(enrollment);
                enrollment.setFailedAttempts(0);
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MFA_ENROLLMENT_RESET",
                        "Demasiados intentos. Vuelve a iniciar la activación.");
            }
            enrollment.setFailedAttempts(attempts);
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MFA_CODE_INVALID", "El código no es correcto.");
        }

        enrollment.setEnabled(true);
        enrollment.setVerifiedAt(now);
        enrollment.setFailedAttempts(0);
        clearEmailCode(enrollment);

        recoveryCodeRepository.deleteAllByUserId(user.getId());
        List<String> codes = newRecoveryCodes();
        codes.forEach(code -> recoveryCodeRepository.save(
                new MfaRecoveryCode(user.getId(), mfaCrypto.hashRecoveryCode(code))));
        auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.MFA_ENABLED, null);
        return new MfaRecoveryCodesResponse(codes);
    }

    /**
     * Pide la contrasena actual (disableMfa). Borra el secreto y los codigos de recuperacion, e invalida los
     * desafios vivos. Si el MFA no estaba activo no cambia nada.
     */
    @Transactional
    public void disable(AuthenticatedUser actor, DisableMfaRequest request) {
        UnknownFields.reject(request.unknownFields());
        AuthAccount account = lockAccount(actor);
        User user = requireUser(actor);
        String currentPassword = request.currentPassword();
        if (currentPassword == null || currentPassword.isEmpty()
                || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw FieldValidationException.of("currentPassword", WRONG_CURRENT_PASSWORD_MESSAGE);
        }

        Optional<MfaEnrollment> enrollment = enrollmentRepository.findByUserIdForUpdate(user.getId())
                .filter(found -> Boolean.TRUE.equals(found.getEnabled()));
        if (enrollment.isEmpty()) {
            return;
        }
        Instant now = authClock.instant();
        MfaEnrollment disabled = enrollment.get();
        disabled.setEnabled(false);
        disabled.setSecretCiphertext(null);
        disabled.setVerifiedAt(null);
        disabled.setLastUsedStep(null);
        disabled.setFailedAttempts(0);
        // Los contadores de envio se conservan: desactivar y reactivar no reinicia el tope de correos.
        clearEmailCode(disabled);
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        challengeRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId())
                .forEach(challenge -> challenge.setInvalidatedAt(now));
        auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.MFA_DISABLED, null);
    }

    /**
     * Para el cambio de contrasena (changePassword): con el MFA activo exige un codigo valido (de la app, el
     * enviado con {@link #sendActionCode} si el metodo es correo, o de recuperacion). Se llama al final,
     * despues de las demas validaciones, para no gastar un codigo en un cambio que igual se rechazaria. El
     * llamador ya bloqueo la cuenta.
     *
     * <p>{@code noRollbackFor}: un codigo por correo incorrecto suma un intento (al 5.o se descarta) y ese
     * conteo debe quedar aunque el cambio responda con error. Ninguna otra escritura ocurre antes.
     */
    @Transactional(noRollbackFor = MfaCodeRejectedException.class)
    public void requireCodeIfEnabled(User user, AuthAccount account, String code) {
        Optional<MfaEnrollment> found = enrollmentRepository.findByUserIdForUpdate(user.getId())
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()));
        if (found.isEmpty()) {
            return;
        }
        MfaEnrollment enrollment = found.get();
        MfaCodeVerifier.EmailCode emailCode = enrollment.getMethod() == MfaMethod.email
                ? pendingEmailCode(enrollment)
                : null;
        Optional<MfaCodeVerifier.Match> match = codeVerifier.verify(enrollment, code, authClock.instant(), emailCode);
        if (match.isEmpty()) {
            if (emailCode != null && enrollment.getEmailCodeHash() != null) {
                int attempts = enrollment.getFailedAttempts() + 1;
                if (attempts >= MAX_ENROLLMENT_ATTEMPTS) {
                    clearEmailCode(enrollment);
                    attempts = 0;
                }
                enrollment.setFailedAttempts(attempts);
            }
            auditService.recordIndependently(user.getTenantId(), user.getId(), account.getId(),
                    AuthAuditService.MFA_FAILED, Map.of("context", "password_change"));
            throw new MfaCodeRejectedException(WRONG_MFA_CODE_MESSAGE);
        }
        if (match.get() == MfaCodeVerifier.Match.EMAIL_CODE) {
            clearEmailCode(enrollment);
            enrollment.setFailedAttempts(0);
        }
        if (match.get() == MfaCodeVerifier.Match.RECOVERY_CODE) {
            auditService.record(user.getTenantId(), user.getId(), account.getId(),
                    AuthAuditService.MFA_RECOVERY_CODE_USED, Map.of("context", "password_change"));
        }
    }

    private static MfaMethod requireMethod(String method) {
        String value = method == null ? "" : method.trim();
        if (value.isEmpty()) {
            throw FieldValidationException.of("method", "Selecciona un método.");
        }
        for (MfaMethod candidate : MfaMethod.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw FieldValidationException.of("method", "Selecciona un método válido.");
    }

    private static boolean hasPendingCode(MfaEnrollment enrollment) {
        return enrollment.getMethod() == MfaMethod.email
                ? enrollment.getEmailCodeHash() != null
                : enrollment.getSecretCiphertext() != null;
    }

    /** Codigo por correo fuera del login: ligado al usuario (ver MfaCrypto.hashEmailCode). */
    private static MfaCodeVerifier.EmailCode pendingEmailCode(MfaEnrollment enrollment) {
        return new MfaCodeVerifier.EmailCode(
                enrollment.getUserId(), enrollment.getEmailCodeHash(), enrollment.getEmailCodeExpiresAt());
    }

    private static void clearEmailCode(MfaEnrollment enrollment) {
        enrollment.setEmailCodeHash(null);
        enrollment.setEmailCodeExpiresAt(null);
    }

    private AuthAccount lockAccount(AuthenticatedUser actor) {
        return authAccountRepository.findByUserIdForUpdate(actor.userId())
                .orElseThrow(MfaService::unauthenticated);
    }

    private User requireUser(AuthenticatedUser actor) {
        return userRepository.findById(actor.userId())
                .filter(found -> found.getTenantId().equals(actor.tenantId()))
                .orElseThrow(MfaService::unauthenticated);
    }

    /** XXXX-XXXX en hexadecimal mayuscula (generateRecoveryCode de MockAuthRepository), sin repetidos. */
    private static List<String> newRecoveryCodes() {
        HexFormat hex = HexFormat.of().withUpperCase();
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < RECOVERY_CODES_COUNT) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            String value = hex.formatHex(bytes);
            codes.add(value.substring(0, 4) + "-" + value.substring(4));
        }
        return List.copyOf(codes);
    }

    /** Formato de Key Uri de Google Authenticator: otpauth://totp/Emisor:cuenta?secret=...&issuer=Emisor. */
    private static String otpauthUri(String email, String base32Secret) {
        String label = encode(ISSUER + ":" + email);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(ISSUER)
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Debes iniciar sesion.");
    }
}
