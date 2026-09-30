package com.omniretail.backend.administration.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignTenantSubscriptionRequest(@NotNull UUID planId) {
}
