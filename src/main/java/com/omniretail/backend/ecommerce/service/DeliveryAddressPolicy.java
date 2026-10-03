package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.dto.AddressRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reglas de direcciones de entrega, identicas a {@code src/config/delivery-address-policy.ts} y
 * {@code address.validation.ts} del frontend: mismos limites, caracteres, orden y mensajes. Se valida
 * el valor ya recortado, que es el que se guarda (igual que MockAddressRepository.assertValidAddress).
 */
@Component
@RequiredArgsConstructor
public class DeliveryAddressPolicy {

    static final int LABEL_MAX = 35;
    static final int RECIPIENT_NAME_MAX = 60;
    static final int LINE1_MAX = 200;
    static final int LINE2_MAX = 200;
    static final int REFERENCES_MAX = 300;

    private static final Pattern PERSON_NAME_INVALID_CHARACTER = Pattern.compile("[^\\p{L}\\p{M} '-]");
    private static final Pattern DELIVERY_ADDRESS = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .,#!'/-]*");
    private static final Pattern POSTAL_CODE = Pattern.compile("\\d{5}");

    private final GuatemalaLocations guatemalaLocations;

    /** @return errores por campo; vacio si la direccion es valida. */
    public Map<String, String> validate(AddressRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String label = trim(request.label());
        if (label.isEmpty()) {
            errors.put("label", "El nombre de la dirección es obligatorio.");
        } else if (label.length() > LABEL_MAX) {
            errors.put("label", "El nombre de la dirección no puede superar " + LABEL_MAX + " caracteres.");
        }

        String recipientName = trim(request.recipientName());
        if (recipientName.isEmpty()) {
            errors.put("recipientName", "El destinatario es obligatorio.");
        } else if (!isValidRecipientName(recipientName)) {
            errors.put("recipientName", "El destinatario permite letras, espacios, apóstrofes y guiones; máximo "
                    + RECIPIENT_NAME_MAX + " caracteres.");
        }

        String line1 = trim(request.line1());
        if (line1.isEmpty()) {
            errors.put("line1", "La dirección es obligatoria.");
        } else if (!isValidDeliveryAddress(line1, LINE1_MAX)) {
            errors.put("line1", "La dirección permite letras, números, puntos y guiones; máximo "
                    + LINE1_MAX + " caracteres.");
        }

        String line2 = trim(request.line2());
        if (!line2.isEmpty() && !isValidDeliveryAddress(line2, LINE2_MAX)) {
            errors.put("line2", "El complemento permite solo letras, números y espacios; máximo "
                    + LINE2_MAX + " caracteres.");
        }

        String references = trim(request.references());
        if (!references.isEmpty() && !isValidDeliveryAddress(references, REFERENCES_MAX)) {
            errors.put("references", "Las referencias permiten letras, números, espacios y comas; máximo "
                    + REFERENCES_MAX + " caracteres.");
        }

        String department = trim(request.stateOrDepartment());
        if (department.isEmpty()) {
            errors.put("stateOrDepartment", "El departamento es obligatorio.");
        } else if (!guatemalaLocations.isDepartment(department)) {
            errors.put("stateOrDepartment", "Selecciona un departamento válido.");
        }

        // El municipio solo se puede verificar contra un departamento valido.
        String city = trim(request.city());
        if (city.isEmpty()) {
            errors.put("city", "El municipio es obligatorio.");
        } else if (guatemalaLocations.isDepartment(department)
                && !guatemalaLocations.isMunicipalityOf(department, city)) {
            errors.put("city", "Selecciona un municipio que pertenezca al departamento elegido.");
        }

        String postalCode = trim(request.postalCode());
        if (!postalCode.isEmpty() && !POSTAL_CODE.matcher(postalCode).matches()) {
            errors.put("postalCode", "El código postal debe tener 5 dígitos.");
        }

        return errors;
    }

    private static boolean isValidRecipientName(String value) {
        return value.length() <= RECIPIENT_NAME_MAX && !PERSON_NAME_INVALID_CHARACTER.matcher(value).find();
    }

    private static boolean isValidDeliveryAddress(String value, int max) {
        return value.length() <= max && DELIVERY_ADDRESS.matcher(value).matches();
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
