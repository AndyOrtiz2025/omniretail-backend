package com.omniretail.backend.shared.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GuatemalaPhoneRules {

    static final String REGEX = "^(?:\\+502 )?([0-9]{4})-?([0-9]{4})$";
    static final Pattern PATTERN = Pattern.compile(REGEX);

    private GuatemalaPhoneRules() {
    }

    static boolean isValid(String value) {
        return value == null || value.isBlank() || PATTERN.matcher(value.trim()).matches();
    }

    static Matcher matcher(String value) {
        return PATTERN.matcher(value.trim());
    }
}
