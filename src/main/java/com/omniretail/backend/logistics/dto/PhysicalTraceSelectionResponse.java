package com.omniretail.backend.logistics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PhysicalTraceSelectionResponse(
        UUID locationId,
        UUID lotId,
        String lotNumber,
        LocalDate expirationDate,
        BigDecimal quantity,
        List<String> serialNumbers) {}
