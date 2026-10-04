package com.omniretail.backend.pos.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record PosDeferredOrderResponse(
        UUID id,
        UUID tenantId,
        UUID branchId,
        String orderNumber,
        OrderSource source,
        UUID customerId,
        JsonNode guestCustomer,
        List<Item> items,
        OrderStatus status,
        DeliveryMethod deliveryMethod,
        TransportMode transportMode,
        JsonNode deliveryAddress,
        JsonNode storePickupContact,
        JsonNode notificationContact,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal shippingTotal,
        BigDecimal total,
        String trackingToken,
        String idempotencyKey,
        String idempotencyFingerprint,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(
            UUID id,
            UUID orderId,
            UUID productId,
            UUID promotionId,
            String skuSnapshot,
            String nameSnapshot,
            BigDecimal quantity,
            BigDecimal inventoryQuantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal,
            JsonNode fulfillmentComponents) {}

    public static PosDeferredOrderResponse from(
            Order order, List<OrderItem> items, JsonMapper jsonMapper) {
        return new PosDeferredOrderResponse(
                order.getId(),
                order.getTenantId(),
                order.getBranchId(),
                order.getOrderNumber(),
                order.getSource(),
                order.getCustomerId(),
                json(order.getGuestCustomer(), jsonMapper),
                items.stream().map(item -> new Item(
                        item.getId(),
                        item.getOrderId(),
                        item.getProductId(),
                        item.getPromotionId(),
                        item.getSkuSnapshot(),
                        item.getNameSnapshot(),
                        item.getQuantity(),
                        item.getInventoryQuantity(),
                        item.getUnitPrice(),
                        item.getDiscount(),
                        item.getSubtotal(),
                        json(item.getFulfillmentComponents(), jsonMapper))).toList(),
                order.getStatus(),
                order.getDeliveryMethod(),
                order.getTransportMode(),
                json(order.getDeliveryAddress(), jsonMapper),
                json(order.getStorePickupContact(), jsonMapper),
                json(order.getNotificationContact(), jsonMapper),
                order.getSubtotal(),
                order.getDiscountTotal(),
                order.getShippingTotal(),
                order.getTotal(),
                order.getTrackingToken(),
                order.getIdempotencyKey(),
                order.getIdempotencyFingerprint(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static JsonNode json(String value, JsonMapper jsonMapper) {
        return value == null ? null : jsonMapper.readTree(value);
    }
}
