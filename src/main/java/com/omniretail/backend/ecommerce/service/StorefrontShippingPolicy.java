package com.omniretail.backend.ecommerce.service;

import java.math.BigDecimal;

/** Política temporal de entrega para compras de la tienda en línea. */
public final class StorefrontShippingPolicy {

    public static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("300.00");
    public static final BigDecimal STANDARD_DELIVERY_FEE = new BigDecimal("25.00");

    private StorefrontShippingPolicy() {}

    public static BigDecimal calculate(BigDecimal subtotal) {
        return subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO.setScale(2)
                : STANDARD_DELIVERY_FEE;
    }
}
