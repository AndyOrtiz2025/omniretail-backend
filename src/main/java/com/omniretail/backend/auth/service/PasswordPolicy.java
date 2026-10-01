package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.UserType;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Politica de contrasenas, identica a {@code src/config/auth-policy.ts} del frontend
 * (validatePasswordAgainstPolicy): mismas reglas, mismo orden y mismos mensajes. Toda contrasena
 * nueva se valida aqui; el login no la revalida, para no dejar fuera credenciales anteriores validas.
 * Clientes y empleados comparten las reglas; empleados exigen un minimo mayor.
 */
public enum PasswordPolicy {

    CUSTOMER(8),
    EMPLOYEE(12);

    static final int MAX_LENGTH = 24;

    /**
     * Mismos caracteres que {@code \s} y {@code String.prototype.trim()} en JavaScript. El {@code \s}
     * de Java solo cubre ASCII y {@code strip()} no incluye U+00A0 ni U+FEFF.
     */
    private static final String JS_WHITESPACE =
            "\\t\\n\\x{0B}\\f\\r \\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}";

    private static final Pattern HAS_WHITESPACE = Pattern.compile("[" + JS_WHITESPACE + "]");
    private static final Pattern EDGE_WHITESPACE =
            Pattern.compile("^[" + JS_WHITESPACE + "]+|[" + JS_WHITESPACE + "]+$");
    private static final Pattern ALL_NUMERIC = Pattern.compile("\\p{N}+");
    private static final Pattern HAS_UPPERCASE = Pattern.compile("\\p{Lu}");
    private static final Pattern HAS_LOWERCASE = Pattern.compile("\\p{Ll}");
    private static final Pattern HAS_NUMBER = Pattern.compile("\\p{N}");
    private static final Pattern HAS_REAL_SPECIAL = Pattern.compile("[^\\p{L}\\p{N}" + JS_WHITESPACE + "]");

    private static final Set<String> COMMON_OR_COMPROMISED_PASSWORDS = Set.of(
            "password",
            "password1",
            "password1!",
            "password123!",
            "qwerty123!",
            "admin123!",
            "welcome123!",
            "letmein123!");

    private final int minLength;

    PasswordPolicy(int minLength) {
        this.minLength = minLength;
    }

    public static PasswordPolicy forUserType(UserType type) {
        return type == UserType.employee ? EMPLOYEE : CUSTOMER;
    }

    public int minLength() {
        return minLength;
    }

    public String requirementsMessage() {
        return "La contraseña debe tener entre " + minLength + " y " + MAX_LENGTH
                + " caracteres e incluir una mayúscula, una minúscula, un número y un carácter especial.";
    }

    /**
     * @param email correo de la cuenta (contexto autoritativo, no declarado por el cliente); puede ser null.
     * @return el mensaje publico del primer incumplimiento, o vacio si la contrasena es valida.
     */
    public Optional<String> validate(String password, String email) {
        if (password == null || password.isEmpty()) {
            return Optional.of("La contraseña es obligatoria.");
        }
        if (email != null && !email.isEmpty() && normalize(password).equals(normalize(email))) {
            return Optional.of("La contraseña no puede ser igual al correo electrónico.");
        }
        if (password.length() < minLength || password.length() > MAX_LENGTH) {
            return Optional.of(requirementsMessage());
        }
        if (HAS_WHITESPACE.matcher(password).find()) {
            return Optional.of("La contraseña no puede contener espacios.");
        }
        if (ALL_NUMERIC.matcher(password).matches()) {
            return Optional.of("La contraseña no puede contener solo números.");
        }
        if (!HAS_UPPERCASE.matcher(password).find()
                || !HAS_LOWERCASE.matcher(password).find()
                || !HAS_NUMBER.matcher(password).find()
                || !HAS_REAL_SPECIAL.matcher(password).find()) {
            return Optional.of(requirementsMessage());
        }
        if (COMMON_OR_COMPROMISED_PASSWORDS.contains(normalize(password))) {
            return Optional.of("La contraseña es demasiado común o está comprometida.");
        }
        return Optional.empty();
    }

    /** Equivale a {@code value.trim().toLowerCase()} de JavaScript. */
    private static String normalize(String value) {
        return EDGE_WHITESPACE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
