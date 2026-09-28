package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta publica del seguimiento de un pedido (igual a StorefrontOrderTrackingDto del frontend).
 * Nunca incluye datos del cliente, ids internos, tenantId ni el token de seguimiento.
 */
public record OrderTrackingResponse(String orderNumber, String status, BigDecimal total, List<Item> items) {

    public record Item(String sku, String name, BigDecimal quantity, BigDecimal subtotal) {

        public static Item from(OrderItem item) {
            return new Item(item.getSkuSnapshot(), item.getNameSnapshot(), item.getQuantity(), item.getSubtotal());
        }
    }

    public static OrderTrackingResponse from(Order order, List<OrderItem> items) {
        return new OrderTrackingResponse(order.getOrderNumber(), customerStatus(order.getStatus()), order.getTotal(),
                items.stream().map(Item::from).toList());
    }

    /** Mismo mapeo que mapOrderStatusToCustomerStatus del frontend. */
    static String customerStatus(OrderStatus status) {
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
