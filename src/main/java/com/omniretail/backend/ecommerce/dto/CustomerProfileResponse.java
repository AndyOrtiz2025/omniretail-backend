package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

/** Perfil del cliente autenticado, con la forma de {@code Customer.ts} del frontend. */
public record CustomerProfileResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        String code,
        String name,
        String email,
        String phone,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerProfileResponse from(Customer customer) {
        return new CustomerProfileResponse(
                customer.getId(),
                customer.getTenantId(),
                customer.getUserId(),
                customer.getCode(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getStatus(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}
