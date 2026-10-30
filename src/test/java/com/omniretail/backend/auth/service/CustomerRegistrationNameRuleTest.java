package com.omniretail.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unitario: regla de nombre del registro de clientes, sin contexto de Spring ni Testcontainers. */
class CustomerRegistrationNameRuleTest {

    /** La expresion original, con backtracking: referencia para comprobar que los posesivos no cambian nada. */
    private static final Pattern BACKTRACKING_REFERENCE =
            Pattern.compile("^\\p{L}[\\p{L}\\p{M}]*(?:[ '-][\\p{L}\\p{M}]+)*$");

    @ParameterizedTest
    @ValueSource(strings = {
            "Ana", "José Pérez", "María O'Neil", "Ana-Lucía", "Zoë Ñúñez", "Łukasz Wójcik",
            "José", "A", "Ana Lucía de los Ángeles",
            "", " ", "Ana ", " Ana", "Ana  Pérez", "Ana--Pérez", "Ana-", "-Ana", "Ana'", "'Ana",
            "Ana2", "<script>alert(1)</script>", "Ana<b>", "Ana & Luis", "Robert'); DROP TABLE users;--",
            "Ana\u0000Pérez", "Ana\nPérez", "Ana\tPérez", "Ana\u001bPérez", "Ana\u200BPérez", "Ana\u202EPérez",
            "́Ana", "Ana - Pérez"})
    void possessiveRuleAcceptsExactlyTheSameNamesAsTheBacktrackingOne(String name) {
        assertThat(CustomerRegistrationService.PERSON_NAME.matcher(name).matches())
                .isEqualTo(BACKTRACKING_REFERENCE.matcher(name).matches());
    }

    @Test
    void adversarialLongInputIsRejectedQuicklyWithoutStackOverflow() {
        // Muchas palabras validas y un caracter invalido al final: el peor caso para el backtracking.
        String adversarial = "ab ".repeat(50_000) + "ab!";

        assertThatCode(() -> assertThat(timedMatch(adversarial)).isLessThan(Duration.ofSeconds(1)))
                .doesNotThrowAnyException();
        assertThat(CustomerRegistrationService.PERSON_NAME.matcher(adversarial).matches()).isFalse();
    }

    @Test
    void normalizeNameProducesTheCanonicalFormBeforeValidation() {
        assertThat(CustomerRegistrationService.normalizeName("  José   O’Neil–Ruiz ")).isEqualTo("José O'Neil-Ruiz");
        assertThat(CustomerRegistrationService.normalizeName(null)).isEmpty();
    }

    private static Duration timedMatch(String input) {
        long start = System.nanoTime();
        CustomerRegistrationService.PERSON_NAME.matcher(input).matches();
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
