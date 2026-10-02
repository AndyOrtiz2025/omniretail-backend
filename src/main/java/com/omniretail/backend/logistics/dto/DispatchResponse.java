package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.logistics.entity.DispatchStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DispatchResponse(
        UUID orderId,
        OrderStatus orderStatus,
        UUID dispatchId,
        DispatchStatus dispatchStatus,
        TransportMode transportMode,
        String carrierName,
        String trackingNumber,
        Instant dispatchedAt,
        List<DispatchPackageResponse> packages,
        boolean idempotent) {}
