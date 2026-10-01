package com.omniretail.backend.administration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record EmployeeAuthSummariesRequest(
        @NotNull @Size(max = 100) List<@NotNull UUID> userIds) {
}
