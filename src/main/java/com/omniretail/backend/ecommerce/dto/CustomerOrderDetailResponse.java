package com.omniretail.backend.ecommerce.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Detalle completo y seguro de un pedido para el cliente autenticado.
 * Coincide de forma exacta con CustomerOrderDetailDto del frontend.
 */
public record CustomerOrderDetailResponse(
        String orderNumber,
        String status,
        Instant createdAt,
        String trackingToken,
        BigDecimal subtotal,
        BigDecimal shippingTotal,
        BigDecimal total,
        DeliveryAddressDto deliveryAddress,
        List<ItemDto> items,
        PaymentDto payment) {

    public record DeliveryAddressDto(
            String recipientName,
            String recipientPhone,
            String line1,
            String line2,
            String city,
            String department,
            String country,
            String references) {}

    public record ItemDto(
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {}

    public record PaymentDto(
            String method,
            String status,
            String reference) {}
}
