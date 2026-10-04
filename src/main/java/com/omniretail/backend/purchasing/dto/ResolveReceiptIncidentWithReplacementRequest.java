package com.omniretail.backend.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** Resolución total con reposición: replacementQuantity debe igualar la cantidad afectada. */
public record ResolveReceiptIncidentWithReplacementRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal replacementQuantity,
        List<@Valid TrackingDetailRequest> trackingDetails) {}
