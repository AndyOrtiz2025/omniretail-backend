package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;

public record PackingFinalizeResponse(
        PackingDetailResponse packing, boolean idempotent, OrderStatus orderStatus) {}
