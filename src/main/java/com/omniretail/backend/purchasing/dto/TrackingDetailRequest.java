package com.omniretail.backend.purchasing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrackingDetailRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal baseQuantity,
        @Size(max = 100) String lotNumber,
        LocalDate expirationDate,
        List<@NotBlank @Size(max = 100) String> serialNumbers) {}
