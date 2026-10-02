package com.omniretail.backend.logistics.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
public record ConfirmDispatchRequest(@NotBlank @Size(max=128) String operationId, @Size(max=200) String carrierName, @Size(max=200) String trackingNumber, List<@Valid PackageRequest> packages) {
 public record PackageRequest(@NotBlank @Size(max=80) String number, @DecimalMin(value="0.000", inclusive=false) BigDecimal weight, @Size(max=500) String description) {}
}
