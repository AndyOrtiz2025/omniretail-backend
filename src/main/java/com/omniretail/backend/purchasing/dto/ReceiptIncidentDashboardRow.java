package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import java.time.Instant;
import java.util.UUID;

/**
 * Fila de incidencia de recepción para el dashboard de administración, con recepción, orden de compra,
 * proveedor y sucursal resueltos por join. Los campos de las tablas relacionadas pueden venir nulos.
 */
public record ReceiptIncidentDashboardRow(
        UUID id,
        ReceiptIncidentType incidentType,
        String notes,
        Instant createdAt,
        UUID branchId,
        String receiptNumber,
        String purchaseOrderNumber,
        UUID supplierId,
        String supplierName,
        String branchName) {}
