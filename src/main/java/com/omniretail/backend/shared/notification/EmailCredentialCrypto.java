package com.omniretail.backend.shared.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Cifrado de las credenciales SMTP de los tenants (y de los cuerpos de correo que esperan reintento).
 * AES-256-GCM con una subclave HMAC-SHA256 derivada de EMAIL_CREDENTIAL_ENCRYPTION_KEY con una etiqueta propia
 * (nunca se reutiliza la subclave ni la variable del MFA). IV aleatorio de 12 bytes por cifrado.
 *
 * <p>El id del tenant va como dato asociado (AAD): un texto cifrado copiado a la fila de otro tenant no se
 * puede descifrar. Los cuerpos de correo usan ademas un sufijo en el AAD para que no se puedan intercambiar
 * con la credencial. Los errores nunca incluyen el valor.
 *
 * <p>Solo hay una version de clave activa ({@link #KEY_VERSION}). Se guarda en cada fila para poder
 * migrar una rotacion: una fila con otra version falla al descifrar y el tenant debe volver a guardarla.
 */
@Component
public class EmailCredentialCrypto {

    /** Version de la clave maestra vigente; va en {@code encryption_key_version}. */
    public static final int KEY_VERSION = 1;

    private static final String AES_LABEL = "omniretail/email/sender-credential/aes-256-gcm";
    private static final String PAYLOAD_AAD_SUFFIX = "|email-delivery-payload";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Resultado de cifrar: ambos en Base64, listos para columnas de texto. */
    public record Encrypted(String ciphertext, String iv, int keyVersion) {
    }

    private final SecretKeySpec aesKey;

    public EmailCredentialCrypto(EmailCredentialProperties properties) {
        byte[] master = properties.encryptionKey().getBytes(StandardCharsets.UTF_8);
        this.aesKey = new SecretKeySpec(hmac(master, AES_LABEL.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public Encrypted encryptCredential(String plain, UUID tenantId) {
        return encrypt(plain, aad(tenantId, ""));
    }

    public String decryptCredential(Encrypted stored, UUID tenantId) {
        return decrypt(stored, aad(tenantId, ""));
    }

    public Encrypted encryptPayload(String plain, UUID tenantId) {
        return encrypt(plain, aad(tenantId, PAYLOAD_AAD_SUFFIX));
    }

    public String decryptPayload(Encrypted stored, UUID tenantId) {
        return decrypt(stored, aad(tenantId, PAYLOAD_AAD_SUFFIX));
    }

    private Encrypted encrypt(String plain, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(
                    Base64.getEncoder().encodeToString(encrypted), Base64.getEncoder().encodeToString(iv), KEY_VERSION);
        } catch (GeneralSecurityException ex) {
            // Sin causa: nunca se arrastra el valor en claro.
            throw new IllegalStateException("No se pudo cifrar la credencial de correo.");
        }
    }

    private String decrypt(Encrypted stored, byte[] aad) {
        try {
            if (stored.keyVersion() != KEY_VERSION) {
                throw new IllegalStateException("Version de clave de correo no soportada.");
            }
            byte[] iv = Base64.getDecoder().decode(stored.iv());
            byte[] ciphertext = Base64.getDecoder().decode(stored.ciphertext());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // Nunca se incluye el valor guardado ni el descifrado en el mensaje.
            throw new IllegalStateException("No se pudo descifrar la credencial de correo.");
        }
    }

    private static byte[] aad(UUID tenantId, String suffix) {
        return (tenantId.toString() + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 no disponible.");
        }
    }
}
