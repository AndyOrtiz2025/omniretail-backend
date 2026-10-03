package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.dto.CreatePaymentMethodRequest;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reglas de tarjetas guardadas, identicas a {@code paymentMethod.validation.ts} y
 * MockCustomerPaymentMethodRepository.assertValidPaymentMethod del frontend: mismas listas cerradas
 * ({@code config/card-brands.ts}, {@code config/guatemala-banks.ts}), limites y mensajes. El mes actual
 * se calcula en la zona de Guatemala, donde opera la plataforma.
 */
@Component
public class PaymentMethodPolicy {

    static final List<String> CARD_BRANDS = List.of("Visa", "Mastercard", "American Express", "Discover");

    static final List<String> GUATEMALA_BANKS = List.of(
            "Banco Industrial",
            "Banco G&T Continental",
            "Banco de Desarrollo Rural (BANRURAL)",
            "BAC Credomatic",
            "Banco Agromercantil (BAM)",
            "Banco Promerica",
            "Banco Azteca",
            "VivaBanco",
            "Banco Ficohsa");

    /** MAX_EXPIRATION_YEARS_AHEAD de card-brands.ts. */
    static final int MAX_EXPIRATION_YEARS_AHEAD = 20;
    static final int CARDHOLDER_NAME_MAX = 60;
    static final ZoneId PLATFORM_ZONE = ZoneId.of("America/Guatemala");

    private static final Pattern LAST4 = Pattern.compile("\\d{4}");

    /** @return errores por campo de una tarjeta nueva; vacio si es valida. */
    public Map<String, String> validateCreate(CreatePaymentMethodRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String brand = trim(request.brand());
        if (brand.isEmpty()) {
            errors.put("brand", "La marca de la tarjeta es obligatoria.");
        } else if (!CARD_BRANDS.contains(brand)) {
            errors.put("brand", "Selecciona una marca de tarjeta válida.");
        }

        String issuingBank = trim(request.issuingBank());
        if (issuingBank.isEmpty()) {
            errors.put("issuingBank", "El banco emisor es obligatorio.");
        } else if (!GUATEMALA_BANKS.contains(issuingBank)) {
            errors.put("issuingBank", "Selecciona un banco emisor válido.");
        }

        if (!LAST4.matcher(trim(request.last4())).matches()) {
            errors.put("last4", "Ingresa exactamente los últimos 4 dígitos.");
        }

        validateExpirationAndHolder(request.expirationMonth(), request.expirationYear(), request.cardholderName(),
                errors);
        return errors;
    }

    /** @return errores por campo de la edicion (vencimiento y titular); vacio si es valida. */
    public Map<String, String> validateUpdate(Integer expirationMonth, Integer expirationYear, String cardholderName) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateExpirationAndHolder(expirationMonth, expirationYear, cardholderName, errors);
        return errors;
    }

    private static void validateExpirationAndHolder(
            Integer month, Integer year, String cardholderName, Map<String, String> errors) {
        boolean validMonth = month != null && month >= 1 && month <= 12;
        if (!validMonth) {
            errors.put("expirationMonth", "El mes debe estar entre 1 y 12.");
        }

        YearMonth current = YearMonth.now(PLATFORM_ZONE);
        int maxYear = current.getYear() + MAX_EXPIRATION_YEARS_AHEAD;
        if (year == null) {
            errors.put("expirationYear", "El año debe ser el actual o uno posterior.");
        } else if (year < current.getYear() || (validMonth && YearMonth.of(year, month).isBefore(current))) {
            errors.put("expirationYear", "La tarjeta está vencida.");
        } else if (year > maxYear) {
            errors.put("expirationYear", "El año de expiración no puede ser mayor a " + maxYear + ".");
        }

        if (trim(cardholderName).length() > CARDHOLDER_NAME_MAX) {
            errors.put("cardholderName", "El nombre en la tarjeta no puede superar " + CARDHOLDER_NAME_MAX
                    + " caracteres.");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
