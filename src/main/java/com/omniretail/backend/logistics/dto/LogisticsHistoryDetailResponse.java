package com.omniretail.backend.logistics.dto;

import java.util.List;

public record LogisticsHistoryDetailResponse(
        LogisticsHistoryRowResponse summary,
        List<LogisticsHistoryLineResponse> lines,
        List<DispatchPackageResponse> packages) {}
