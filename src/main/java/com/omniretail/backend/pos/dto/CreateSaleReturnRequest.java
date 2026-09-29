package com.omniretail.backend.pos.dto;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.math.BigDecimal; import java.util.*;
public record CreateSaleReturnRequest(@NotBlank @Size(max=1000) String reason,@NotEmpty @Valid List<Line> lines) {
 public record Line(@NotNull UUID saleItemId,@NotNull @DecimalMin(value="0.001") BigDecimal quantity) {}
}
