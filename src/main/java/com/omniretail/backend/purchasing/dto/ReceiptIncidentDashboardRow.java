package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import java.time.Instant;

/**
 * Fila de incidencia de recepción para el listado de recientes del dashboard de administración,
 * con recepción, orden de compra, proveedor y sucursal resueltos por join.
 */
public record ReceiptIncidentDashboardRow(
        ReceiptIncidentType incidentType,
        String notes,
        Instant createdAt,
        String receiptNumber,
        String purchaseOrderNumber,
        String supplierName,
        String branchName) {}
