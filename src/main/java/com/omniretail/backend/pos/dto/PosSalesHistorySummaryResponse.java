package com.omniretail.backend.pos.dto;

public record PosSalesHistorySummaryResponse(
        long total,
        long completed,
        long partiallyReturned,
        long returned,
        long cancelled) {}
