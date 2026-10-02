package com.omniretail.backend.logistics.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickingJpaMappingTest {

    @Test
    void usesTheCanonicalFrontendEnums() {
        assertThat(names(PickingSourceType.values())).containsExactly("order", "transfer");
        assertThat(names(PickingStatus.values()))
                .containsExactly("pending", "assigned", "in_progress", "completed", "cancelled");
        assertThat(names(PickingItemStatus.values()))
                .containsExactly("pending", "partial", "completed", "incident");
        assertThat(names(PickingPriority.values()))
                .containsExactly("low", "normal", "high", "urgent");
        assertThat(names(PickingIncidentType.values()))
                .containsExactly(
                        "missing",
                        "damaged",
                        "invalid_lot_serial",
                        "quantity_difference",
                        "location_empty");
        assertThat(names(PickingIncidentStatus.values())).containsExactly("open", "resolved");
    }

    @Test
    void mapsGenericSourceIdentityAndOptionalOrderCompatibility() throws NoSuchFieldException {
        assertRequiredImmutableColumn(PickingOrder.class, "sourceType", "source_type");
        assertRequiredImmutableColumn(PickingOrder.class, "sourceId", "source_id");
        assertOptionalImmutableColumn(PickingOrder.class, "orderId", "order_id");
        assertRequiredImmutableColumn(PickingItem.class, "sourceLineId", "source_line_id");
        assertOptionalImmutableColumn(PickingItem.class, "orderItemId", "order_item_id");

        assertStringEnum(PickingOrder.class, "sourceType");
        assertStringEnum(PickingOrder.class, "status");
        assertStringEnum(PickingItem.class, "status");
    }

    @Test
    void mapsInventoryQuantitiesAsNumericTwelveThree() throws NoSuchFieldException {
        for (String fieldName : new String[] {"requestedQuantity", "pickedQuantity"}) {
            Column column = PickingItem.class.getDeclaredField(fieldName).getAnnotation(Column.class);
            assertThat(column.precision()).isEqualTo(12);
            assertThat(column.scale()).isEqualTo(3);
        }
        Column affected = PickingIncident.class
                .getDeclaredField("quantityAffected")
                .getAnnotation(Column.class);
        assertThat(affected.precision()).isEqualTo(12);
        assertThat(affected.scale()).isEqualTo(3);
    }

    @Test
    void repositoryExposesTenantAndBranchScopedSourceLookup() throws NoSuchMethodException {
        assertThat(PickingOrderRepository.class
                        .getMethod(
                                "findByTenantIdAndBranchIdAndSourceTypeAndSourceId",
                                UUID.class,
                                UUID.class,
                                PickingSourceType.class,
                                UUID.class)
                        .getReturnType())
                .isEqualTo(Optional.class);
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }

    private static void assertRequiredImmutableColumn(
            Class<?> type, String fieldName, String columnName) throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getAnnotation(NotNull.class)).isNotNull();
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }

    private static void assertOptionalImmutableColumn(
            Class<?> type, String fieldName, String columnName) throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getAnnotation(NotNull.class)).isNull();
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isTrue();
        assertThat(column.updatable()).isFalse();
    }

    private static void assertStringEnum(Class<?> type, String fieldName)
            throws NoSuchFieldException {
        Enumerated enumerated = type.getDeclaredField(fieldName).getAnnotation(Enumerated.class);
        assertThat(enumerated).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
    }
}
