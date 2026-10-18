package com.omniretail.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.administration.entity.UserType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Mismos casos que scripts/verify-password-policy.ts del frontend, mas los mensajes exactos. */
class PasswordPolicyTest {

    private static final String CUSTOMER_REQUIREMENTS =
            "La contraseña debe tener entre 8 y 64 caracteres e incluir una mayúscula, una minúscula, un número y un carácter especial.";
    private static final String TOO_MANY_BYTES = "La contraseña es demasiado larga. Las tildes, la ñ y los "
            + "emojis ocupan más espacio: usa una más corta o con menos caracteres especiales.";
    private static final String EMPLOYEE_REQUIREMENTS =
            "La contraseña debe tener entre 12 y 64 caracteres e incluir una mayúscula, una minúscula, un número y un carácter especial.";

    @Test
    void limitsMatchFrontend() {
        assertThat(PasswordPolicy.CUSTOMER.minLength()).isEqualTo(8);
        assertThat(PasswordPolicy.EMPLOYEE.minLength()).isEqualTo(12);
        assertThat(PasswordPolicy.MAX_LENGTH).isEqualTo(64);
        assertThat(PasswordPolicy.MAX_BYTES).isEqualTo(72);
        assertThat(PasswordPolicy.forUserType(UserType.customer)).isEqualTo(PasswordPolicy.CUSTOMER);
        assertThat(PasswordPolicy.forUserType(UserType.employee)).isEqualTo(PasswordPolicy.EMPLOYEE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcd1!xy", "Abcdef1!", "Ñandú#2026"})
    void customerAcceptsValidPasswords(String password) {
        assertThat(PasswordPolicy.CUSTOMER.validate(password, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Ab1!xyz", // debajo del minimo
        "Aa1!xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", // 65 caracteres
        "abcdef1!", // sin mayuscula
        "ABCDEF1!", // sin minuscula
        "Abcdefg!", // sin numero
        "Abcdefg1", // sin especial
        "12345678M",
        "12345678m"
    })
    void customerRejectsWithRequirementsMessage(String password) {
        assertThat(PasswordPolicy.CUSTOMER.validate(password, null)).contains(CUSTOMER_REQUIREMENTS);
    }

    @Test
    void employeeAcceptsValidPasswords() {
        assertThat(PasswordPolicy.EMPLOYEE.validate("Abcdefgh1!xy", null)).isEmpty();
        assertThat(PasswordPolicy.EMPLOYEE.validate("Marjym2026!Segura", null)).isEmpty();
        assertThat(PasswordPolicy.EMPLOYEE.validate("Melbyn#Seguro2026", "melbyn@gmail.com"))
                .as("puede incluir la parte local del correo")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Abcdefg1!xy", // 11 caracteres
        "Aa1!xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", // 65 caracteres
        "abcdefghij1!",
        "ABCDEFGHIJ1!",
        "AbcdefghijK!",
        "AbcdefghijK1",
        "Abcdef1!",
        "Marjym2026!"
    })
    void employeeRejectsWithRequirementsMessage(String password) {
        assertThat(PasswordPolicy.EMPLOYEE.validate(password, null)).contains(EMPLOYEE_REQUIREMENTS);
    }

    @Test
    void rejectsEmptyPassword() {
        assertThat(PasswordPolicy.CUSTOMER.validate("", null)).contains("La contraseña es obligatoria.");
        assertThat(PasswordPolicy.CUSTOMER.validate(null, null)).contains("La contraseña es obligatoria.");
    }

    @Test
    void rejectsPasswordEqualToEmailIgnoringCaseAndEdgeWhitespace() {
        String message = "La contraseña no puede ser igual al correo electrónico.";
        assertThat(PasswordPolicy.CUSTOMER.validate("melbyn@gmail.com", "MELBYN@GMAIL.COM")).contains(message);
        assertThat(PasswordPolicy.EMPLOYEE.validate("melbyn@gmail.com", "MELBYN@GMAIL.COM")).contains(message);
        assertThat(PasswordPolicy.CUSTOMER.validate("Admin1!@Example.com", " admin1!@example.com ")).contains(message);
    }

    @Test
    void acceptsUpToSixtyFourCharacters() {
        String sixtyFour = "Aa1!" + "x".repeat(60);
        assertThat(sixtyFour).hasSize(64);
        assertThat(PasswordPolicy.CUSTOMER.validate(sixtyFour, null)).isEmpty();
        assertThat(PasswordPolicy.EMPLOYEE.validate(sixtyFour, null)).isEmpty();
        // Las de 24 de antes siguen siendo validas.
        assertThat(PasswordPolicy.CUSTOMER.validate("Aa1!" + "x".repeat(20), null)).isEmpty();
    }

    @Test
    void acceptsExactlySeventyTwoBytes() {
        String seventyTwoBytes = "Aa1!" + "ñ".repeat(34); // 38 caracteres: 4 + 34 x 2 bytes
        assertThat(seventyTwoBytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(PasswordPolicy.CUSTOMER.validate(seventyTwoBytes, null)).isEmpty();
        assertThat(PasswordPolicy.EMPLOYEE.validate(seventyTwoBytes, null)).isEmpty();
    }

    @Test
    void rejectsMoreThanSeventyTwoBytesWithItsOwnMessage() {
        String emoji = new String(Character.toChars(0x1F600)); // 2 unidades UTF-16, 4 bytes
        String seventyThreeBytes = "Aa1!" + "ñ".repeat(34) + "x"; // 39 caracteres, 73 bytes
        String emojis = "Aa1!" + emoji.repeat(18); // 40 unidades UTF-16, 76 bytes
        String accented = "Áa1!" + "é".repeat(40); // 44 caracteres, 86 bytes
        for (String password : List.of(seventyThreeBytes, emojis, accented)) {
            assertThat(password.length()).isLessThanOrEqualTo(PasswordPolicy.MAX_LENGTH);
            assertThat(password.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
            assertThat(PasswordPolicy.CUSTOMER.validate(password, null)).contains(TOO_MANY_BYTES);
            assertThat(PasswordPolicy.EMPLOYEE.validate(password, null)).contains(TOO_MANY_BYTES);
        }
    }

    @Test
    void rejectsSpacesIncludingUnicodeWhitespace() {
        String message = "La contraseña no puede contener espacios.";
        assertThat(PasswordPolicy.CUSTOMER.validate("Abc 1!xyz", null)).contains(message);
        assertThat(PasswordPolicy.CUSTOMER.validate("Abc 1!xyz", null)).contains(message);
        assertThat(PasswordPolicy.CUSTOMER.validate("Abc　1!xyz", null)).contains(message);
    }

    @Test
    void rejectsOnlyNumbers() {
        assertThat(PasswordPolicy.CUSTOMER.validate("12345678", null)).contains("La contraseña no puede contener solo números.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password1!", "Welcome123!", "Qwerty123!"})
    void customerRejectsCommonPasswords(String password) {
        assertThat(PasswordPolicy.CUSTOMER.validate(password, null))
                .contains("La contraseña es demasiado común o está comprometida.");
    }

    @Test
    void employeeRejectsCommonPassword() {
        assertThat(PasswordPolicy.EMPLOYEE.validate("Password123!", null))
                .contains("La contraseña es demasiado común o está comprometida.");
    }
}
