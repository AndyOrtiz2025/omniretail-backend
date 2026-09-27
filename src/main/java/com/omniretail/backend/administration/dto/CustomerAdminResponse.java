package com.omniretail.backend.administration.dto;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerAdminResponse(
        UUID id,
        UUID userId,
        String code,
        String name,
        String email,
        String phone,
        UUID segmentId,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt,
        long purchaseCount,
        List<CustomerProductPurchaseDto> topProducts) {

    public record CustomerProductPurchaseDto(String productName, BigDecimal totalQuantity) {}

    public static CustomerAdminResponse of(
            Customer customer,
            long purchaseCount,
            List<CustomerProductPurchaseDto> topProducts) {
        return new CustomerAdminResponse(
                customer.getId(),
                customer.getUserId(),
                customer.getCode(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getSegmentId(),
                customer.getStatus(),
                customer.getCreatedAt(),
                customer.getUpdatedAt(),
                purchaseCount,
                topProducts != null ? topProducts : List.of());
    }
}
