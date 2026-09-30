package com.omniretail.backend.administration.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
public record UpdateSubscriptionAddonsRequest(@NotNull @Size(max = 2) List<@NotBlank String> addonCodes) {}
