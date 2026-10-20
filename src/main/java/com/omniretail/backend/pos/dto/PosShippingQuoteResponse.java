package com.omniretail.backend.pos.dto;

import java.math.BigDecimal;

/**
 * Envío a domicilio que se sumará a la venta diferida, para que el POS cobre el total correcto.
 * {@code freeShippingThreshold} nulo significa que nunca es gratis.
 */
public record PosShippingQuoteResponse(
        BigDecimal subtotal,
        BigDecimal shippingTotal,
        BigDecimal total,
        BigDecimal freeShippingThreshold) {
}
