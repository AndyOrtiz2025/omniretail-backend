package com.omniretail.backend.logistics.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class PickingItemUpdateOperationJpaMappingTest {

    @Test
    void mapsTheAppendOnlyIdempotencyContract() throws Exception {
        assertThat(PickingItemUpdateOperation.class.getAnnotation(Table.class).name())
                .isEqualTo("picking_item_update_operations");
        assertColumn("tenantId", "tenant_id", false, true);
        assertColumn("pickingItemId", "picking_item_id", false, true);
        assertColumn("operationId", "operation_id", false, true);
        assertColumn("fingerprint", "fingerprint", false, true);
        assertColumn("resultItem", "result_item", false, true);

        Field result = PickingItemUpdateOperation.class.getDeclaredField("resultItem");
        assertThat(result.getAnnotation(JdbcTypeCode.class).value()).isEqualTo(SqlTypes.JSON);
    }

    @Test
    void criticalMutationsExposePessimisticWriteLocks() throws Exception {
        assertThat(PickingOrderRepository.class
                        .getMethod("findByScopeAndIdForUpdate", UUID.class, UUID.class, UUID.class)
                        .getAnnotation(Lock.class)
                        .value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(PickingItemRepository.class
                        .getMethod(
                                "findByScopeAndIdForUpdate",
                                UUID.class,
                                UUID.class,
                                UUID.class,
                                UUID.class)
                        .getAnnotation(Lock.class)
                        .value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private static void assertColumn(
            String fieldName, String columnName, boolean nullable, boolean immutable)
            throws Exception {
        Column column = PickingItemUpdateOperation.class
                .getDeclaredField(fieldName)
                .getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isEqualTo(nullable);
        assertThat(column.updatable()).isEqualTo(!immutable);
    }
}
