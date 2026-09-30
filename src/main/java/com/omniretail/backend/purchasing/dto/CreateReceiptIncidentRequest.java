package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateReceiptIncidentRequest(
        @NotNull ReceiptIncidentType incidentType,
        UUID goodsReceiptItemId,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal quantityAffected,
        @NotBlank String notes) {}
