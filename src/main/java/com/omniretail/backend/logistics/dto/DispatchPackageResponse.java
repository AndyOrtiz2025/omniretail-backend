package com.omniretail.backend.logistics.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DispatchPackageResponse(
        UUID id,
        String number,
        BigDecimal weight,
        String description) {}
