package com.omniretail.backend.inventory.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryMovementDisplayTypeTest {

    @Test
    void mapsOnlyGroundedProducerReferences() {
        assertThat(display(InventoryMovementType.in, "goods_receipt")).isEqualTo("purchase_in");
        assertThat(display(InventoryMovementType.out, "POS_SALE")).isEqualTo("sale");
        assertThat(display(InventoryMovementType.out, "POS_KIT_SALE")).isEqualTo("sale");
        assertThat(display(InventoryMovementType.in, "POS_SALE_RETURN")).isEqualTo("return");
        assertThat(display(InventoryMovementType.in, "POS_KIT_SALE_RETURN")).isEqualTo("return");
        assertThat(display(InventoryMovementType.in, "POS_SALE_VOID")).isEqualTo("void");
        assertThat(display(InventoryMovementType.in, "POS_KIT_SALE_VOID")).isEqualTo("void");
        assertThat(display(InventoryMovementType.out, "dispatch")).isEqualTo("dispatch");
    }

    @Test
    void arbitraryAdjustmentReferencesAndTransfersKeepRawFallback() {
        assertThat(display(InventoryMovementType.in, "caller_defined")).isEqualTo("in");
        assertThat(display(InventoryMovementType.out, "caller_defined")).isEqualTo("out");
        assertThat(display(InventoryMovementType.transfer, null)).isEqualTo("transfer");
    }

    private static String display(InventoryMovementType type, String referenceType) {
        InventoryMovement movement = InventoryMovement.builder()
                .tenantId(UUID.randomUUID())
                .branchId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .type(type)
                .reason("Prueba")
                .quantity(BigDecimal.ONE)
                .referenceType(referenceType)
                .build();
        return InventoryMovementDisplayType.from(movement).jsonValue();
    }
}
