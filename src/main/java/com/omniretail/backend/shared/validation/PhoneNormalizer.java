package com.omniretail.backend.shared.validation;

import java.util.regex.Matcher;

public final class PhoneNormalizer {

    private PhoneNormalizer() {
    }

    public static String normalize(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        Matcher matcher = GuatemalaPhoneRules.matcher(phone);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Formato de teléfono de Guatemala no admitido");
        }
        return "+502 " + matcher.group(1) + "-" + matcher.group(2);
    }
}
