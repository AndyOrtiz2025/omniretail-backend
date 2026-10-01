package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.NotNull;

public record PackingChecklistRequest(
        @NotNull Boolean packageProtectionChecked,
        @NotNull Boolean documentIncludedChecked,
        @NotNull Boolean recipientVerifiedChecked) {}
