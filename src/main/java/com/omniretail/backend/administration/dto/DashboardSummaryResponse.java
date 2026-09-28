package com.omniretail.backend.administration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DashboardSummaryResponse(
        SalesSummaryDto salesToday,
        SalesSummaryDto salesMonth,
        List<SalesByBranchDto> salesByBranch,
        List<DailySaleDto> dailySalesMonth,
        StockAlertsDto stockAlerts,
        List<StockAlertsByBranchDto> stockAlertsByBranch,
        long pendingOrders,
        PendingOrdersByStatusDto pendingOrdersByStatus,
        List<PendingOrdersByBranchDto> pendingOrdersByBranch,
        IncidentAnalyticsDto incidentAnalytics,
        List<IncidentDto> latestIncidents,
        List<TopProductDto> topProducts) {

    public record SalesSummaryDto(BigDecimal amount, long count) {}

    public record SalesByBranchDto(String branchName, BigDecimal amount, long count) {}

    public record DailySaleDto(String date, BigDecimal amount, long count) {}

    public record StockAlertsDto(long outOfStock, long lowStock) {}

    public record StockAlertsByBranchDto(
            UUID branchId, String branchName, long outOfStock, long lowStock, long total) {}

    public record PendingOrdersByStatusDto(
            long confirmed,
            long preparing,
            long picking,
            long packing,
            @JsonProperty("ready_for_dispatch") long readyForDispatch) {}

    public record PendingOrdersByBranchDto(String branchName, long count) {}

    public record IncidentAnalyticsDto(
            long totalCurrentMonth,
            List<IncidentTypeCountDto> byType,
            List<IncidentSupplierCountDto> bySupplier) {}

    public record IncidentTypeCountDto(String typeName, long count) {}

    public record IncidentSupplierCountDto(String supplierName, long count) {}

    public record IncidentDto(
            String description,
            String typeName,
            String createdAt,
            String receiptNumber,
            String branchName,
            String purchaseOrderNumber,
            String transferNumber,
            String supplierName,
            String originBranchName) {}

    public record TopProductDto(String productName, BigDecimal totalQuantity, BigDecimal totalRevenue) {}
}
