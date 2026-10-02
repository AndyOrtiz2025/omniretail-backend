package com.omniretail.backend.inventory.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class InventoryTransferJpaMappingTest {

    @Test
    void usesTheCanonicalPersistenceEnums() {
        assertThat(names(InventoryTransferRequestStatus.values()))
                .containsExactly("requested", "approved", "rejected", "cancelled");
        assertThat(names(InventoryTransferStatus.values()))
                .containsExactly("preparing", "inTransit", "received", "cancelled");
        assertThat(names(InventoryTransferReason.values()))
                .containsExactly(
                        "replenishment",
                        "demandCoverage",
                        "urgentRequest",
                        "inventoryBalancing",
                        "other");
    }

    @Test
    void mapsRequestQuantitiesEnumsAndVersion() throws Exception {
        assertNumericColumn(
                InventoryTransferRequest.class, "requestedQuantity", "requested_quantity");
        assertStringEnum(InventoryTransferRequest.class, "reason");
        assertStringEnum(InventoryTransferRequest.class, "status");
        assertVersion(InventoryTransferRequest.class);
    }

    @Test
    void mapsTransferEnumsAndVersion() throws Exception {
        assertStringEnum(InventoryTransfer.class, "status");
        assertStringEnum(InventoryTransfer.class, "reason");
        assertVersion(InventoryTransfer.class);
    }

    @Test
    void mapsAllItemQuantitiesAsNumericTwelveThree() throws Exception {
        assertNumericColumn(
                InventoryTransferItem.class, "requestedQuantity", "requested_quantity");
        assertNumericColumn(
                InventoryTransferItem.class, "dispatchedQuantity", "dispatched_quantity");
        assertNumericColumn(InventoryTransferItem.class, "receivedQuantity", "received_quantity");
        assertNumericColumn(InventoryTransferReceiptItem.class, "quantity", "quantity");
    }

    @Test
    void keepsSourceRequestOptionalAndImmutable() throws Exception {
        Field field = InventoryTransferItem.class.getDeclaredField("sourceRequestId");
        Column column = field.getAnnotation(Column.class);

        assertThat(column.name()).isEqualTo("source_request_id");
        assertThat(column.nullable()).isTrue();
        assertThat(column.updatable()).isFalse();
    }

    @Test
    void repositoriesExposeTenantScopedPessimisticLocks() throws Exception {
        Method requestLock = InventoryTransferRequestRepository.class.getMethod(
                "findForUpdateByTenantIdAndId", UUID.class, UUID.class);
        Method transferLock = InventoryTransferRepository.class.getMethod(
                "findForUpdateByTenantIdAndId", UUID.class, UUID.class);

        assertThat(requestLock.getReturnType()).isEqualTo(Optional.class);
        assertThat(requestLock.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(transferLock.getReturnType()).isEqualTo(Optional.class);
        assertThat(transferLock.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }

    private static void assertNumericColumn(
            Class<?> entityType, String fieldName, String columnName) throws Exception {
        Field field = entityType.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getType()).isEqualTo(BigDecimal.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.precision()).isEqualTo(12);
        assertThat(column.scale()).isEqualTo(3);
    }

    private static void assertStringEnum(Class<?> entityType, String fieldName) throws Exception {
        Enumerated enumerated =
                entityType.getDeclaredField(fieldName).getAnnotation(Enumerated.class);
        assertThat(enumerated).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
    }

    private static void assertVersion(Class<?> entityType) throws Exception {
        Field version = entityType.getDeclaredField("version");
        assertThat(version.getType()).isEqualTo(Long.class);
        assertThat(version.getAnnotation(Version.class)).isNotNull();
        assertThat(version.getAnnotation(Column.class).nullable()).isFalse();
    }
}
