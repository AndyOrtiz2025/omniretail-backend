package com.omniretail.backend.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.omniretail.backend.ecommerce.entity.Address;
import java.time.Instant;
import java.util.UUID;

/** Direccion del cliente autenticado, con la forma de {@code Address.ts} del frontend. */
public record AddressResponse(
        UUID id,
        UUID tenantId,
        UUID customerId,
        String label,
        String recipientName,
        String line1,
        String line2,
        String city,
        String stateOrDepartment,
        String postalCode,
        String country,
        String references,
        @JsonProperty("isDefault") boolean isDefault,
        Instant createdAt,
        Instant updatedAt) {

    public static AddressResponse from(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getTenantId(),
                address.getCustomerId(),
                address.getLabel(),
                address.getRecipientName(),
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getStateOrDepartment(),
                address.getPostalCode(),
                address.getCountry(),
                address.getReferences(),
                Boolean.TRUE.equals(address.getIsDefault()),
                address.getCreatedAt(),
                address.getUpdatedAt());
    }
}
