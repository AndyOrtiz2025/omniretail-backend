package com.omniretail.backend.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GuatemalaPhoneTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"+502 2323-1232", "2323-1232", "23231232"})
    void acceptsSupportedGuatemalaPhoneFormats(String phone) {
        assertThat(validator.validate(new PhoneValue(phone))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void acceptsOptionalPhoneValues(String phone) {
        assertThat(validator.validate(new PhoneValue(phone))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2323123", "232A-1232", "+503 2323-1232", "+502 2323-12321"})
    void rejectsUnsupportedPhoneFormats(String phone) {
        assertThat(validator.validate(new PhoneValue(phone)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("El teléfono debe tener 8 dígitos (formato +502 0000-0000)."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+502 2323-1232", "2323-1232", "23231232"})
    void normalizesSupportedFormatsToCanonicalPhone(String phone) {
        assertThat(PhoneNormalizer.normalize(phone)).isEqualTo("+502 2323-1232");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void normalizesOptionalPhoneValuesToNull(String phone) {
        assertThat(PhoneNormalizer.normalize(phone)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2323123", "232A-1232", "+503 2323-1232", "+502 2323-12321"})
    void rejectsUnsupportedFormatsWhenNormalizing(String phone) {
        assertThatThrownBy(() -> PhoneNormalizer.normalize(phone)).isInstanceOf(IllegalArgumentException.class);
    }

    private record PhoneValue(@GuatemalaPhone String phone) {
    }
}
