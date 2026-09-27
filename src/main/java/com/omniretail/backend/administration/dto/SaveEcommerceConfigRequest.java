package com.omniretail.backend.administration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SaveEcommerceConfigRequest(
        boolean enabled,
        @NotBlank @Size(max = 120) String storeName,
        @Size(max = 500) String logoUrl,
        @Pattern(
                        regexp = "^$|^(\\+502\\s?)?[2-8]\\d{3}-?\\d{4}$|^[2-8]\\d{7}$",
                        message = "El teléfono público debe tener 8 dígitos.")
                String contactPhone,
        @Email @Size(max = 254) String contactEmail,
        boolean requireAccountForCheckout,
        boolean guestTrackingEnabled,
        List<String> allowedDeliveryMethods,
        List<String> allowedPaymentMethods,
        UUID defaultBranchId) {
}
