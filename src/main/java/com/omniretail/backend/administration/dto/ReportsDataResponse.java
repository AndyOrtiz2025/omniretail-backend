package com.omniretail.backend.administration.dto;

import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.entity.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportsDataResponse(
        UUID tenantId,
        List<SalesReportRow> sales,
        List<PurchasesReportRow> purchases,
        List<MovementReportRow> movements,
        List<PaymentReportRow> payments) {

    public record SalesReportRow(
            String number,
            Instant date,
            UUID branchId,
            String branchName,
            SaleStatus status,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            String channel,
            String origin) {}

    public record PurchasesReportRow(
            String number,
            Instant date,
            UUID branchId,
            String branchName,
            UUID supplierId,
            String supplierName,
            String status,
            BigDecimal subtotal,
            BigDecimal total) {}

    public record MovementReportRow(
            Instant date,
            UUID branchId,
            String branchName,
            UUID productId,
            String productName,
            InventoryMovementType type,
            BigDecimal quantity,
            String reason) {}

    public record PaymentReportRow(
            Instant date,
            PaymentMethod method,
            PaymentStatus status,
            BigDecimal amount,
            String reference,
            String origin) {}
}
