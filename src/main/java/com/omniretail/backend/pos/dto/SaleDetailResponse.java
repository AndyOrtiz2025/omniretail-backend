package com.omniretail.backend.pos.dto;

import java.util.List;

public record SaleDetailResponse(SaleResponse sale, List<SaleItemResponse> items,
        List<PaymentResponse> payments) {}
