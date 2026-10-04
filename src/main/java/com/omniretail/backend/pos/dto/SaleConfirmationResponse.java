package com.omniretail.backend.pos.dto;

import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.pos.entity.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Complete, additive result of a POS sale confirmation. */
public record SaleConfirmationResponse(
        UUID id,
        String number,
        UUID branchId,
        UUID cashShiftId,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        Instant createdAt,
        SaleStatus status,
        UUID customerId,
        UUID sourceOrderId,
        SaleDocumentResponse document,
        List<SaleItemResponse> items,
        List<PaymentResponse> payments,
        List<InventoryMovementResponse> inventoryEffects,
        CashMovementResponse cashMovement,
        PosDeferredOrderResponse order,
        PosPickingOrderResponse pickingOrder,
        boolean idempotent) {

    public SaleConfirmationResponse(
            UUID id,
            String number,
            UUID branchId,
            UUID cashShiftId,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            Instant createdAt,
            SaleStatus status,
            UUID customerId,
            UUID sourceOrderId,
            SaleDocumentResponse document,
            List<SaleItemResponse> items,
            List<PaymentResponse> payments,
            List<InventoryMovementResponse> inventoryEffects,
            CashMovementResponse cashMovement,
            boolean idempotent) {
        this(id, number, branchId, cashShiftId, subtotal, discountTotal, taxTotal, total,
                createdAt, status, customerId, sourceOrderId, document, items, payments,
                inventoryEffects, cashMovement, null, null, idempotent);
    }

    public static SaleConfirmationResponse from(
            SaleResponse sale,
            List<SaleItemResponse> items,
            List<PaymentResponse> payments,
            List<InventoryMovementResponse> inventoryEffects,
            CashMovementResponse cashMovement,
            boolean idempotent) {
        return from(sale, items, payments, inventoryEffects, cashMovement, null, null, idempotent);
    }

    public static SaleConfirmationResponse from(
            SaleResponse sale,
            List<SaleItemResponse> items,
            List<PaymentResponse> payments,
            List<InventoryMovementResponse> inventoryEffects,
            CashMovementResponse cashMovement,
            PosDeferredOrderResponse order,
            PosPickingOrderResponse pickingOrder,
            boolean idempotent) {
        return new SaleConfirmationResponse(
                sale.id(), sale.number(), sale.branchId(), sale.cashShiftId(), sale.subtotal(),
                sale.discountTotal(), sale.taxTotal(), sale.total(), sale.createdAt(), sale.status(),
                sale.customerId(), sale.sourceOrderId(), sale.document(), items, payments,
                inventoryEffects, cashMovement, order, pickingOrder, idempotent);
    }
}
