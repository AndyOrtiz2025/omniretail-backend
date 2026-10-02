package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.Set;
import org.springframework.http.HttpStatus;

public enum InventoryMovementDisplayType {
    purchase_in,
    transfer_out,
    transfer_in,
    inventory_adjustment,
    shrinkage,
    manual_in,
    manual_out,
    sale,
    dispatch,
    return_,
    void_,
    store_pickup,
    in,
    out,
    adjustment,
    transfer;

    public static final Set<String> SALE_REFERENCES = Set.of("POS_SALE", "POS_KIT_SALE");
    public static final Set<String> RETURN_REFERENCES = Set.of("POS_SALE_RETURN", "POS_KIT_SALE_RETURN");
    public static final Set<String> VOID_REFERENCES = Set.of("POS_SALE_VOID", "POS_KIT_SALE_VOID");
    public static final Set<String> SPECIFIC_REFERENCES = Set.of(
            "goods_receipt", "POS_SALE", "POS_KIT_SALE", "POS_SALE_RETURN",
            "POS_KIT_SALE_RETURN", "POS_SALE_VOID", "POS_KIT_SALE_VOID", "dispatch");

    public static InventoryMovementDisplayType from(InventoryMovement movement) {
        String referenceType = movement.getReferenceType();
        if ("goods_receipt".equalsIgnoreCase(referenceType)) return purchase_in;
        if (referenceType != null && SALE_REFERENCES.contains(referenceType)) return sale;
        if (referenceType != null && RETURN_REFERENCES.contains(referenceType)) return return_;
        if (referenceType != null && VOID_REFERENCES.contains(referenceType)) return void_;
        if ("dispatch".equalsIgnoreCase(referenceType)) return dispatch;
        return switch (movement.getType()) {
            case in -> in;
            case out -> out;
            case transfer -> transfer;
        };
    }

    public String jsonValue() {
        return switch (this) {
            case return_ -> "return";
            case void_ -> "void";
            default -> name();
        };
    }

    public static InventoryMovementDisplayType parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if ("return".equals(normalized)) return return_;
        if ("void".equals(normalized)) return void_;
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_MOVEMENT_DISPLAY_TYPE_INVALID",
                    "El displayType de movimiento no es valido.");
        }
    }
}
