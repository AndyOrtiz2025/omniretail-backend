package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.entity.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;

public record StorefrontCheckoutResponse(
        String orderNumber,
        String trackingToken,
        BigDecimal total,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        List<Item> items) {

    public record Item(String sku, String name, BigDecimal quantity, BigDecimal subtotal) {}
}
