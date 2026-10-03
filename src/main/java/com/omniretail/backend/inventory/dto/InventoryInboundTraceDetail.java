package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InventoryInboundTraceDetail(
        BigDecimal baseQuantity,
        String lotNumber,
        LocalDate expirationDate,
        List<String> serialNumbers) {}
