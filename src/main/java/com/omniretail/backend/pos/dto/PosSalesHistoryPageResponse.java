package com.omniretail.backend.pos.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record PosSalesHistoryPageResponse(
        List<PosSalesHistoryRowResponse> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages,
        PosSalesHistorySummaryResponse summary) {

    public static PosSalesHistoryPageResponse from(
            Page<?> page,
            List<PosSalesHistoryRowResponse> items,
            PosSalesHistorySummaryResponse summary) {
        return new PosSalesHistoryPageResponse(
                items,
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                summary);
    }
}
