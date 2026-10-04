package com.omniretail.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MfaCryptoTest {

    private static final String KEY = "una-clave-de-pruebas-de-al-menos-32-bytes";

    private final MfaCrypto crypto = new MfaCrypto(new MfaProperties(KEY));

    @Test
    void secretRoundTripsAndIsNeverStoredInClear() {
        UUID userId = UUID.randomUUID();
        byte[] secret = Totp.newSecret();

        String stored = crypto.encryptSecret(secret, userId);

        assertThat(crypto.decryptSecret(stored, userId)).isEqualTo(secret);
        assertThat(stored).doesNotContain(Totp.base32Encode(secret));
        assertThat(HexFormat.of().formatHex(Base64.getDecoder().decode(stored)))
                .doesNotContain(HexFormat.of().formatHex(secret));
        // IV aleatorio: cifrar dos veces el mismo secreto da resultados distintos.
        assertThat(crypto.encryptSecret(secret, userId)).isNotEqualTo(stored);
    }

    @Test
    void secretOfOneUserCannotBeDecryptedForAnother() {
        String stored = crypto.encryptSecret(Totp.newSecret(), UUID.randomUUID());

        assertThrows(IllegalStateException.class, () -> crypto.decryptSecret(stored, UUID.randomUUID()));
    }

    @Test
    void otherMasterKeyCannotDecrypt() {
        UUID userId = UUID.randomUUID();
        String stored = crypto.encryptSecret(Totp.newSecret(), userId);
        MfaCrypto other = new MfaCrypto(new MfaProperties("otra-clave-de-pruebas-de-al-menos-32-bytes"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> other.decryptSecret(stored, userId));
        assertThat(error.getMessage()).doesNotContain(stored);
    }

    @Test
    void recoveryCodeHashIsDeterministicAndDependsOnTheKey() {
        String hash = crypto.hashRecoveryCode("ABCD-1234");

        assertThat(hash).hasSize(64).isEqualTo(crypto.hashRecoveryCode("ABCD-1234"));
        assertThat(crypto.hashRecoveryCode("ABCD-1235")).isNotEqualTo(hash);
        assertThat(new MfaCrypto(new MfaProperties("otra-clave-de-pruebas-de-al-menos-32-bytes"))
                .hashRecoveryCode("ABCD-1234")).isNotEqualTo(hash);
    }

    @Test
    void missingOrShortKeyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new MfaProperties(null));
        assertThrows(IllegalStateException.class, () -> new MfaProperties("corta"));
        assertThat(new MfaProperties(KEY).toString()).doesNotContain(KEY);
    }
}
