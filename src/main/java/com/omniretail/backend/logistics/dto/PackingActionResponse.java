package com.omniretail.backend.logistics.dto;

public record PackingActionResponse(
        PackingDetailResponse packing,
        boolean idempotent) {}
