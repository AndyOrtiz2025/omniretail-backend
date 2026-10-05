package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailCredentialCryptoTest {

    private static final String KEY = "una-clave-de-correo-de-pruebas-de-32-bytes";
    private static final String SECRET = "abcdEFGH12345678";

    private final EmailCredentialCrypto crypto = new EmailCredentialCrypto(new EmailCredentialProperties(KEY));

    @Test
    void credentialRoundTripsAndIsNeverStoredInClear() {
        UUID tenantId = UUID.randomUUID();

        EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(SECRET, tenantId);

        assertThat(crypto.decryptCredential(encrypted, tenantId)).isEqualTo(SECRET);
        assertThat(encrypted.ciphertext()).doesNotContain(SECRET);
        assertThat(encrypted.keyVersion()).isEqualTo(EmailCredentialCrypto.KEY_VERSION);
        // IV aleatorio de 12 bytes: dos cifrados del mismo valor difieren.
        assertThat(java.util.Base64.getDecoder().decode(encrypted.iv())).hasSize(12);
        EmailCredentialCrypto.Encrypted again = crypto.encryptCredential(SECRET, tenantId);
        assertThat(again.iv()).isNotEqualTo(encrypted.iv());
        assertThat(again.ciphertext()).isNotEqualTo(encrypted.ciphertext());
    }

    @Test
    void ciphertextCopiedToAnotherTenantCannotBeDecrypted() {
        EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(SECRET, UUID.randomUUID());

        assertThrows(IllegalStateException.class, () -> crypto.decryptCredential(encrypted, UUID.randomUUID()));
    }

    @Test
    void errorsNeverLeakTheValue() {
        UUID tenantId = UUID.randomUUID();
        EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(SECRET, tenantId);

        IllegalStateException wrongTenant = assertThrows(
                IllegalStateException.class, () -> crypto.decryptCredential(encrypted, UUID.randomUUID()));
        IllegalStateException garbage = assertThrows(IllegalStateException.class,
                () -> crypto.decryptCredential(
                        new EmailCredentialCrypto.Encrypted("%%no-base64%%", encrypted.iv(), 1), tenantId));
        IllegalStateException otherKey = assertThrows(IllegalStateException.class,
                () -> new EmailCredentialCrypto(new EmailCredentialProperties("otra-clave-de-correo-de-pruebas-32-bytes"))
                        .decryptCredential(encrypted, tenantId));

        for (IllegalStateException error : new IllegalStateException[] {wrongTenant, garbage, otherKey}) {
            assertThat(error.getMessage()).doesNotContain(SECRET, encrypted.ciphertext(), encrypted.iv());
            assertThat(error.getCause()).isNull();
        }
    }

    @Test
    void unsupportedKeyVersionFailsToDecrypt() {
        UUID tenantId = UUID.randomUUID();
        EmailCredentialCrypto.Encrypted encrypted = crypto.encryptCredential(SECRET, tenantId);

        assertThrows(IllegalStateException.class, () -> crypto.decryptCredential(
                new EmailCredentialCrypto.Encrypted(encrypted.ciphertext(), encrypted.iv(), 99), tenantId));
    }

    @Test
    void payloadAndCredentialAreNotInterchangeable() {
        UUID tenantId = UUID.randomUUID();
        EmailCredentialCrypto.Encrypted payload = crypto.encryptPayload("{\"body\":\"hola\"}", tenantId);

        assertThat(crypto.decryptPayload(payload, tenantId)).isEqualTo("{\"body\":\"hola\"}");
        assertThrows(IllegalStateException.class, () -> crypto.decryptCredential(payload, tenantId));
    }

    @Test
    void missingOrShortKeyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new EmailCredentialProperties(null));
        assertThrows(IllegalStateException.class, () -> new EmailCredentialProperties("corta"));
        assertThat(new EmailCredentialProperties(KEY).toString()).doesNotContain(KEY);
    }
}
