package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.SaasPlanCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record UpdateSaasPlanRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$") String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @Positive int maxBranches,
        @Positive int maxUsers,
        @Positive int maxProducts,
        @NotNull @DecimalMin("0.00") BigDecimal priceMonthly,
        @NotNull SaasPlanCurrency currency,
        @NotEmpty List<@NotBlank @Size(max = 100) String> capabilities) {
}
