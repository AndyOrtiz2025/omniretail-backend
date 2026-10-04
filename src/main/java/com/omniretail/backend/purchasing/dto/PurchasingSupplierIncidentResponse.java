package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Historial de incidencias de recepción de un proveedor. Una incidencia general (sin línea) trae
 * {@code productId} y {@code productName} nulos. Cada fila ya viene resuelta por join.
 */
public record PurchasingSupplierIncidentResponse(
        UUID id,
        ReceiptIncidentType incidentType,
        ReceiptIncidentStatus status,
        BigDecimal quantityAffected,
        String notes,
        Instant createdAt,
        Instant resolvedAt,
        UUID goodsReceiptId,
        String receiptNumber,
        UUID purchaseOrderId,
        String purchaseOrderNumber,
        UUID branchId,
        UUID productId,
        String productName) {}
