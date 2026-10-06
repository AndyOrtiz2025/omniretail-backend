package com.omniretail.backend.pos.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.entity.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PosSalesHistoryRowResponse(
        UUID saleId,
        String saleNumber,
        Instant createdAt,
        String customerDisplayName,
        BigDecimal total,
        SaleStatus status,
        UUID sourceOrderId,
        DeliveryMethod deliveryMethod,
        OrderStatus operationalStatus) {}
