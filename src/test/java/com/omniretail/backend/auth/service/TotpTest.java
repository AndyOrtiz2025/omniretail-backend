package com.omniretail.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** Semilla SHA1 del Apendice B de RFC 6238 (20 bytes ASCII). */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** Vectores oficiales de RFC 6238, Apendice B, columna SHA1 (8 digitos). */
    @ParameterizedTest
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130"
    })
    void matchesRfc6238Sha1Vectors(long epochSeconds, String expected) {
        long step = Totp.step(Instant.ofEpochSecond(epochSeconds));

        assertThat(Totp.generate(RFC_SECRET, step, 8)).isEqualTo(expected);
    }

    @Test
    void sixDigitCodeIsTheLastSixDigitsOfTheRfcValue() {
        // RFC 4226: el codigo es el mismo valor truncado modulo 10^digitos.
        assertThat(Totp.generate(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.generate(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
    }

    @Test
    void stepsAreThirtySeconds() {
        assertThat(Totp.step(Instant.ofEpochSecond(0))).isZero();
        assertThat(Totp.step(Instant.ofEpochSecond(29))).isZero();
        assertThat(Totp.step(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(Totp.step(Instant.ofEpochSecond(59))).isEqualTo(1);
    }

    /** Vectores de RFC 4648, seccion 10 (sin relleno, como en otpauth://). */
    @ParameterizedTest
    @CsvSource({"f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
    void base32MatchesRfc4648(String plain, String encoded) {
        byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);

        assertThat(Totp.base32Encode(bytes)).isEqualTo(encoded);
        assertThat(Totp.base32Decode(encoded)).isEqualTo(bytes);
    }

    @Test
    void newSecretIs160BitsAndRoundTripsThroughBase32() {
        byte[] secret = Totp.newSecret();

        assertThat(secret).hasSize(20);
        assertThat(Totp.base32Decode(Totp.base32Encode(secret))).isEqualTo(secret);
        assertThat(Totp.newSecret()).isNotEqualTo(secret);
    }

    @Test
    void invalidBase32IsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Totp.base32Decode("MZ1W"));
    }
}
