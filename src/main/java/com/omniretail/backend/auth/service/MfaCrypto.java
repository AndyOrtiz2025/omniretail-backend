package com.omniretail.backend.auth.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Criptografia del MFA. De la clave maestra (MFA_ENCRYPTION_KEY) se derivan con HMAC-SHA256 y etiquetas
 * distintas tres subclaves independientes: AES-256-GCM para el secreto TOTP, HMAC-SHA256 para los codigos
 * de recuperacion y HMAC-SHA256 para los codigos por correo. Asi una clave nunca se usa para dos propositos.
 *
 * <p>El secreto cifrado se guarda como Base64 de {@code iv (12 bytes) || ciphertext+tag}; el id del
 * usuario va como dato asociado (AAD), de modo que un secreto copiado a la fila de otro usuario no
 * se puede descifrar.
 */
@Component
public class MfaCrypto {

    private static final String AES_LABEL = "omniretail/mfa/totp-secret/aes-256-gcm";
    private static final String RECOVERY_LABEL = "omniretail/mfa/recovery-code/hmac-sha256";
    private static final String EMAIL_CODE_LABEL = "omniretail/mfa/email-code/hmac-sha256";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec recoveryKey;
    private final SecretKeySpec emailCodeKey;

    public MfaCrypto(MfaProperties properties) {
        byte[] master = properties.encryptionKey().getBytes(StandardCharsets.UTF_8);
        this.aesKey = new SecretKeySpec(hmac(master, AES_LABEL.getBytes(StandardCharsets.UTF_8)), "AES");
        this.recoveryKey = new SecretKeySpec(
                hmac(master, RECOVERY_LABEL.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        this.emailCodeKey = new SecretKeySpec(
                hmac(master, EMAIL_CODE_LABEL.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    }

    public String encryptSecret(byte[] secret, UUID userId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(userId));
            byte[] encrypted = cipher.doFinal(secret);
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el secreto MFA.", ex);
        }
    }

    public byte[] decryptSecret(String stored, UUID userId) {
        try {
            byte[] payload = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            cipher.updateAAD(associatedData(userId));
            return cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // Nunca se incluye el valor guardado ni el secreto en el mensaje.
            throw new IllegalStateException("No se pudo descifrar el secreto MFA.", ex);
        }
    }

    /** HMAC-SHA256 en hexadecimal (64 caracteres) de un codigo de recuperacion ya normalizado. */
    public String hashRecoveryCode(String normalizedCode) {
        return hmacHex(recoveryKey, normalizedCode);
    }

    /**
     * HMAC-SHA256 de un codigo de 6 digitos enviado por correo, ligado a {@code bindingId} (el desafio o el
     * usuario): el mismo codigo da hashes distintos en filas distintas, y sin la clave no se puede probar
     * el millon de combinaciones contra la base.
     */
    public String hashEmailCode(UUID bindingId, String code) {
        return hmacHex(emailCodeKey, bindingId + ":" + code);
    }

    private static String hmacHex(SecretKeySpec key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 no disponible.", ex);
        }
    }

    private static byte[] associatedData(UUID userId) {
        return userId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 no disponible.", ex);
        }
    }
}
