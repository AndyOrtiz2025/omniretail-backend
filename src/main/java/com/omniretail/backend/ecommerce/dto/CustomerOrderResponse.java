package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resumen seguro para el historial del cliente autenticado. No expone tenant, sucursal,
 * idempotencia ni snapshots de otros compradores.
 */
public record CustomerOrderResponse(
        UUID id,
        String orderNumber,
        String status,
        int itemCount,
        BigDecimal total,
        Instant createdAt,
        DeliveryMethod deliveryMethod) {

    public static CustomerOrderResponse from(Order order, int itemCount) {
        return new CustomerOrderResponse(
                order.getId(),
                order.getOrderNumber(),
                customerStatus(order.getStatus()),
                itemCount,
                order.getTotal(),
                order.getCreatedAt(),
                order.getDeliveryMethod());
    }

    /** Mismo contrato de estado que el seguimiento público y la pantalla "Mis pedidos". */
    private static String customerStatus(OrderStatus status) {
        return switch (status) {
            case pending -> "pending";
            case confirmed -> "confirmed";
            case preparing, picking, packing, ready_for_pickup, ready_for_dispatch -> "preparing";
            case dispatched -> "sent";
            case delivered -> "delivered";
            case cancelled -> "cancelled";
        };
    }
}
