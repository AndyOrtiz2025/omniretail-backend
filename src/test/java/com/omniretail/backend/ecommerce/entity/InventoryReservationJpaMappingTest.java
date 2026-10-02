package com.omniretail.backend.ecommerce.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryReservationJpaMappingTest {

    @Test
    void supportsOnlyTheInitialFulfillmentSourceTypes() {
        assertThat(Arrays.stream(InventoryReservationSourceType.values())
                        .map(Enum::name))
                .containsExactly("order", "transfer");
    }

    @Test
    void mapsGenericSourceIdentityAsRequiredImmutableColumns() throws NoSuchFieldException {
        assertRequiredImmutableColumn("sourceType", "source_type");
        assertRequiredImmutableColumn("sourceId", "source_id");
        assertRequiredImmutableColumn("sourceLineId", "source_line_id");
        assertRequiredImmutableColumn("quantity", "quantity");

        Enumerated enumerated = InventoryReservation.class
                .getDeclaredField("sourceType")
                .getAnnotation(Enumerated.class);
        assertThat(enumerated).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
    }

    @Test
    void keepsLegacyOrderReferencesOptionalForTransferReservations() throws NoSuchFieldException {
        assertOptionalImmutableColumn("orderId", "order_id");
        assertOptionalImmutableColumn("orderItemId", "order_item_id");
    }

    @Test
    void representsTransferSourceAndLineWithoutLegacyOrderReferences() {
        UUID transferId = UUID.randomUUID();
        UUID transferItemId = UUID.randomUUID();

        InventoryReservation reservation = InventoryReservation.builder()
                .sourceType(InventoryReservationSourceType.transfer)
                .sourceId(transferId)
                .sourceLineId(transferItemId)
                .quantity(java.math.BigDecimal.ONE)
                .build();

        assertThat(reservation.getSourceType()).isEqualTo(InventoryReservationSourceType.transfer);
        assertThat(reservation.getSourceId()).isEqualTo(transferId);
        assertThat(reservation.getSourceLineId()).isEqualTo(transferItemId);
        assertThat(reservation.getOrderId()).isNull();
        assertThat(reservation.getOrderItemId()).isNull();
    }

    @Test
    void repositoryExposesTenantScopedSourceQueries() throws NoSuchMethodException {
        assertThat(InventoryReservationRepository.class.getMethod(
                                "findByTenantIdAndSourceTypeAndSourceId",
                                UUID.class,
                                InventoryReservationSourceType.class,
                                UUID.class)
                        .getReturnType())
                .isEqualTo(List.class);
        assertThat(InventoryReservationRepository.class.getMethod(
                                "findByTenantIdAndSourceTypeAndSourceIdAndStatus",
                                UUID.class,
                                InventoryReservationSourceType.class,
                                UUID.class,
                                InventoryReservationStatus.class)
                        .getReturnType())
                .isEqualTo(List.class);
    }

    private static void assertRequiredImmutableColumn(String fieldName, String columnName)
            throws NoSuchFieldException {
        Field field = InventoryReservation.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getAnnotation(NotNull.class)).isNotNull();
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }

    private static void assertOptionalImmutableColumn(String fieldName, String columnName)
            throws NoSuchFieldException {
        Field field = InventoryReservation.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getAnnotation(NotNull.class)).isNull();
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isTrue();
        assertThat(column.updatable()).isFalse();
    }
}
