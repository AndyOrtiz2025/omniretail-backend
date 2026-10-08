package com.omniretail.backend.pos.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

class SaleReversalOperationJpaMappingTest {

    @Test
    void mapsAppendOnlyVoidIdempotencyContract() throws Exception {
        assertThat(SaleReversalOperation.class.getAnnotation(Table.class).name())
                .isEqualTo("sale_reversal_operations");
        assertColumn("tenantId", "tenant_id");
        assertColumn("branchId", "branch_id");
        assertColumn("saleId", "sale_id");
        assertColumn("operationType", "operation_type");
        assertColumn("idempotencyKey", "idempotency_key");
        assertColumn("fingerprint", "fingerprint");
        assertColumn("reason", "reason");
        assertColumn("executedByUserId", "executed_by_user_id");
        assertColumn("resultPayload", "result_payload");

        Field operationType = SaleReversalOperation.class.getDeclaredField("operationType");
        assertThat(operationType.getAnnotation(Enumerated.class).value())
                .isEqualTo(EnumType.STRING);
        Field result = SaleReversalOperation.class.getDeclaredField("resultPayload");
        assertThat(result.getAnnotation(JdbcTypeCode.class).value()).isEqualTo(SqlTypes.JSON);
    }

    private static void assertColumn(String fieldName, String columnName) throws Exception {
        Column column = SaleReversalOperation.class
                .getDeclaredField(fieldName)
                .getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }
}
