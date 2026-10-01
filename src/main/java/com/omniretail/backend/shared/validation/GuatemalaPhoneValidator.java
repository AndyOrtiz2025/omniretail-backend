package com.omniretail.backend.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class GuatemalaPhoneValidator implements ConstraintValidator<GuatemalaPhone, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return GuatemalaPhoneRules.isValid(value);
    }
}
