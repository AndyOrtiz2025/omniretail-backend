package com.omniretail.backend.logistics.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

class PackingOperationJpaMappingTest {

    @Test
    void mapsAppendOnlyHistoricalIdempotencyContract() throws Exception {
        assertThat(PackingOperation.class.getAnnotation(Table.class).name())
                .isEqualTo("packing_operations");
        assertColumn("tenantId", "tenant_id");
        assertColumn("branchId", "branch_id");
        assertColumn("packingId", "packing_id");
        assertColumn("operationId", "operation_id");
        assertColumn("operationType", "operation_type");
        assertColumn("fingerprint", "fingerprint");
        assertColumn("resultVersion", "result_version");
        assertColumn("resultPacking", "result_packing");

        Field operationType = PackingOperation.class.getDeclaredField("operationType");
        assertThat(operationType.getAnnotation(Enumerated.class).value())
                .isEqualTo(EnumType.STRING);
        Field result = PackingOperation.class.getDeclaredField("resultPacking");
        assertThat(result.getAnnotation(JdbcTypeCode.class).value()).isEqualTo(SqlTypes.JSON);
    }

    private static void assertColumn(String fieldName, String columnName) throws Exception {
        Column column =
                PackingOperation.class.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }
}
