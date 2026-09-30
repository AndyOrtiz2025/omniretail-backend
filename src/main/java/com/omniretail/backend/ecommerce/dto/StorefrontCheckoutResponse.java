package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.entity.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StorefrontCheckoutResponse(
        String orderNumber,
        String trackingToken,
        BigDecimal total,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        boolean guestTrackingEnabled,
        boolean hasInventoryReservations,
        Map<String, Object> deliveryAddress,
        boolean confirmationEmailSent,
        List<Item> items) {

    public record Item(
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal,
            UUID promotionId) {}
}
