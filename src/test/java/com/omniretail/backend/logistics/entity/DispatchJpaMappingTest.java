package com.omniretail.backend.logistics.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.ecommerce.entity.TransportMode;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class DispatchJpaMappingTest {
    @Test
    void dispatchUsesTheExpectedTableAndStringEnums() throws Exception {
        assertThat(Dispatch.class.getAnnotation(Table.class).name()).isEqualTo("dispatches");
        assertStringEnum(Dispatch.class, "sourceType", DispatchSourceType.class);
        assertStringEnum(Dispatch.class, "status", DispatchStatus.class);
        assertStringEnum(Dispatch.class, "transportMode", TransportMode.class);
        assertThat(column(Dispatch.class, "sourceId").nullable()).isFalse();
        assertThat(column(Dispatch.class, "packingId").nullable()).isFalse();
    }

    @Test
    void packageAndOperationUseImmutablePersistenceColumns() throws Exception {
        assertThat(DispatchPackage.class.getAnnotation(Table.class).name()).isEqualTo("dispatch_packages");
        assertThat(column(DispatchPackage.class, "number").length()).isEqualTo(80);
        assertThat(column(DispatchPackage.class, "description").length()).isEqualTo(500);
        assertThat(DispatchOperation.class.getAnnotation(Table.class).name()).isEqualTo("dispatch_operations");
        assertThat(column(DispatchOperation.class, "operationId").length()).isEqualTo(128);
    }

    private static void assertStringEnum(Class<?> type, String field, Class<?> ignored) throws Exception {
        assertThat(type.getDeclaredField(field).getAnnotation(Enumerated.class).value())
                .isEqualTo(EnumType.STRING);
    }

    private static Column column(Class<?> type, String field) throws Exception {
        Field declared = type.getDeclaredField(field);
        return declared.getAnnotation(Column.class);
    }
}
