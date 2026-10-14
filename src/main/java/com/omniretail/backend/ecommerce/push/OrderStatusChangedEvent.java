package com.omniretail.backend.ecommerce.push;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.util.UUID;

public record OrderStatusChangedEvent(
        UUID tenantId,
        UUID customerId,
        UUID orderId,
        OrderStatus status
) {
}
