package com.omniretail.backend.logistics.dto;

public record PackingChecklistResponse(
        boolean packageProtectionChecked,
        boolean documentIncludedChecked,
        boolean recipientVerifiedChecked) {}
