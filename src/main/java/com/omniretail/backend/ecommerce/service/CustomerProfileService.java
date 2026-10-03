package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.ecommerce.dto.CustomerProfileResponse;
import com.omniretail.backend.ecommerce.dto.UpdateCustomerProfileRequest;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.validation.UnknownFields;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Datos personales" del cliente autenticado (updateCustomerProfile.ts / updateProfileForCustomer del
 * mock). Solo nombre y telefono; se copian tambien al User para que {@code /auth/me} los refleje.
 */
@Service
@RequiredArgsConstructor
public class CustomerProfileService {

    /** TEXT_FIELD_POLICY.NAME_MAX_LENGTH de text-field-policy.ts. */
    static final int NAME_MAX_LENGTH = 100;
    /** PHONE_POLICY.DIGITS de contact-policy.ts: en Guatemala todo numero tiene exactamente 8 digitos. */
    static final int PHONE_DIGITS = 8;
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final CurrentUser currentUser;
    private final CurrentCustomerResolver currentCustomerResolver;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CustomerProfileResponse get() {
        return CustomerProfileResponse.from(currentCustomerResolver.require(currentUser.require()));
    }

    @Transactional
    public CustomerProfileResponse update(UpdateCustomerProfileRequest request) {
        AuthenticatedUser actor = currentUser.require();
        Customer customer = currentCustomerResolver.require(actor);
        UnknownFields.reject(request.unknownFields());
        validate(request);

        String name = request.name().trim();
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();
        customer.setName(name);
        customer.setPhone(phone);
        userRepository.findById(actor.userId())
                .filter(user -> user.getTenantId().equals(actor.tenantId()))
                .ifPresent(user -> updateUser(user, name, phone));

        // flush: updatedAt (@UpdateTimestamp) se asigna al escribir y debe viajar en la respuesta.
        return CustomerProfileResponse.from(customerRepository.saveAndFlush(customer));
    }

    /** Mismas reglas y mensajes que profile.validation.ts; devuelve todos los campos con error a la vez. */
    private static void validate(UpdateCustomerProfileRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            errors.put("name", "El nombre es obligatorio.");
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.put("name", "El nombre no puede superar " + NAME_MAX_LENGTH + " caracteres.");
        }

        // Opcional: solo se valida si se escribio algo (validatePhoneNumber de contact-policy.ts).
        String phone = request.phone() == null ? "" : request.phone().trim();
        if (!phone.isEmpty()) {
            if (!DIGITS.matcher(phone).matches()) {
                errors.put("phone", "El teléfono solo puede contener números.");
            } else if (phone.length() != PHONE_DIGITS) {
                errors.put("phone", "El teléfono debe tener exactamente " + PHONE_DIGITS + " dígitos.");
            }
        }

        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private static void updateUser(User user, String name, String phone) {
        user.setName(name);
        user.setPhone(phone);
    }
}
