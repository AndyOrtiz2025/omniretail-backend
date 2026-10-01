package com.omniretail.backend.administration.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderAdminResponse(
        UUID id,
        String orderNumber,
        UUID branchId,
        UUID customerId,
        String guestCustomer,
        OrderStatus status,
        DeliveryMethod deliveryMethod,
        String deliveryAddress,
        String storePickupContact,
        String notificationContact,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal shippingTotal,
        BigDecimal total,
        String trackingToken,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items,
        List<PaymentLine> payments) {

    public record Item(UUID id, UUID productId, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal discount, BigDecimal subtotal) {}

    public record PaymentLine(UUID id, PaymentMethod method, PaymentStatus status,
            BigDecimal amount, String currency, UUID bankAccountId, String reference,
            Boolean externallyVerified) {}

    public static OrderAdminResponse of(Order order, List<OrderItem> items, List<Payment> payments) {
        return new OrderAdminResponse(order.getId(), order.getOrderNumber(), order.getBranchId(),
                order.getCustomerId(), order.getGuestCustomer(), order.getStatus(), order.getDeliveryMethod(),
                order.getDeliveryAddress(), order.getStorePickupContact(), order.getNotificationContact(),
                order.getSubtotal(), order.getDiscountTotal(), order.getShippingTotal(), order.getTotal(),
                order.getTrackingToken(), order.getCreatedAt(), order.getUpdatedAt(),
                items.stream().map(item -> new Item(item.getId(), item.getProductId(), item.getSkuSnapshot(),
                        item.getNameSnapshot(), item.getQuantity(), item.getUnitPrice(), item.getDiscount(),
                        item.getSubtotal())).toList(),
                payments.stream().map(payment -> new PaymentLine(payment.getId(), payment.getMethod(),
                        payment.getStatus(), payment.getAmount(), payment.getCurrency(), payment.getBankAccountId(),
                        payment.getReference(), payment.getExternallyVerified())).toList());
    }
}
