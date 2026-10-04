package com.omniretail.backend.auth.service;

import com.omniretail.backend.auth.entity.MfaEnrollment;
import com.omniretail.backend.auth.repository.MfaRecoveryCodeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Que cuenta como un codigo valido para un usuario (consumeMfaCode de MockAuthRepository): un codigo
 * TOTP de 6 digitos o un codigo de recuperacion {@code XXXX-XXXX} sin usar.
 *
 * <p>TOTP: se acepta el paso actual y uno antes o despues (±30 s de desfase de reloj), pero nunca un paso
 * igual o anterior al ultimo aceptado; asi el mismo codigo no sirve dos veces. El llamador debe tener
 * bloqueado el enrollment ({@code findByUserIdForUpdate}). Aceptar un codigo lo consume como efecto.
 */
@Component
@RequiredArgsConstructor
public class MfaCodeVerifier {

    /** Pasos de tolerancia hacia cada lado del actual. */
    static final int ALLOWED_DRIFT_STEPS = 1;

    private static final Pattern TOTP_CODE = Pattern.compile("\\d{" + Totp.DIGITS + "}");
    private static final Pattern RECOVERY_CODE = Pattern.compile("[0-9A-F]{4}-[0-9A-F]{4}");

    private final MfaCrypto mfaCrypto;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;

    public enum Match { TOTP, RECOVERY_CODE }

    /** Codigo TOTP o de recuperacion, para un MFA activo. */
    public Optional<Match> verify(MfaEnrollment enrollment, String rawCode, Instant now) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (TOTP_CODE.matcher(code).matches()) {
            return verifyTotp(enrollment, code, now) ? Optional.of(Match.TOTP) : Optional.empty();
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        if (RECOVERY_CODE.matcher(normalized).matches()) {
            return recoveryCodeRepository.findUnusedForUpdate(
                            enrollment.getUserId(), mfaCrypto.hashRecoveryCode(normalized))
                    .map(recoveryCode -> {
                        recoveryCode.setUsedAt(now);
                        return Match.RECOVERY_CODE;
                    });
        }
        return Optional.empty();
    }

    /** Solo TOTP (confirmar la activacion: todavia no hay codigos de recuperacion). */
    public boolean verifyTotp(MfaEnrollment enrollment, String rawCode, Instant now) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (enrollment.getSecretCiphertext() == null || !TOTP_CODE.matcher(code).matches()) {
            return false;
        }
        byte[] secret = mfaCrypto.decryptSecret(enrollment.getSecretCiphertext(), enrollment.getUserId());
        long current = Totp.step(now);
        Long lastUsed = enrollment.getLastUsedStep();
        for (long step = current - ALLOWED_DRIFT_STEPS; step <= current + ALLOWED_DRIFT_STEPS; step++) {
            if (lastUsed != null && step <= lastUsed) {
                continue;
            }
            byte[] expected = Totp.generate(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, code.getBytes(StandardCharsets.US_ASCII))) {
                enrollment.setLastUsedStep(step);
                return true;
            }
        }
        return false;
    }
}
