package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.inventory.dto.InventoryPhysicalSelection;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.repository.InventoryLotRepository;
import com.omniretail.backend.inventory.service.InventoryPhysicalSelectionCodec;
import com.omniretail.backend.logistics.entity.PickingItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PickingTraceProjectionServiceTest {

    @Test
    void batchProjectionLoadsLotsOnceForMultiplePickingLines() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID firstLotId = UUID.randomUUID();
        UUID secondLotId = UUID.randomUUID();
        InventoryPhysicalSelectionCodec codec = mock(InventoryPhysicalSelectionCodec.class);
        InventoryLotRepository lots = mock(InventoryLotRepository.class);
        PickingTraceProjectionService service = new PickingTraceProjectionService(codec, lots);
        Product product = Product.builder()
                .productType(ProductType.physical)
                .trackingStock(true)
                .trackingLot(true)
                .trackingSerial(true)
                .build();
        ReflectionTestUtils.setField(product, "id", productId);
        PickingItem first = item(productId, locationId, "first");
        PickingItem second = item(productId, locationId, "second");
        InventoryPhysicalSelection firstSelection = new InventoryPhysicalSelection(
                locationId, firstLotId, new BigDecimal("1.000"), List.of("SER-1"));
        InventoryPhysicalSelection secondSelection = new InventoryPhysicalSelection(
                locationId, secondLotId, new BigDecimal("1.000"), List.of("SER-2"));
        when(codec.decode("first")).thenReturn(List.of(firstSelection));
        when(codec.decode("second")).thenReturn(List.of(secondSelection));
        when(codec.withoutLocation(List.of(firstSelection), locationId)).thenReturn(List.of());
        when(codec.withoutLocation(List.of(secondSelection), locationId)).thenReturn(List.of());
        when(lots.findByTenantIdAndIdIn(tenantId, List.of(firstLotId, secondLotId)))
                .thenReturn(List.of(
                        lot(firstLotId, productId, "LOT-1"),
                        lot(secondLotId, productId, "LOT-2")));

        Map<UUID, List<com.omniretail.backend.logistics.dto.PhysicalTraceSelectionResponse>> result =
                service.projectAll(tenantId, List.of(first, second), Map.of(productId, product));

        assertThat(result.get(first.getId())).singleElement().satisfies(trace ->
                assertThat(trace.lotNumber()).isEqualTo("LOT-1"));
        assertThat(result.get(second.getId())).singleElement().satisfies(trace ->
                assertThat(trace.serialNumbers()).containsExactly("SER-2"));
        verify(lots, times(1)).findByTenantIdAndIdIn(
                tenantId, List.of(firstLotId, secondLotId));
    }

    private static PickingItem item(UUID productId, UUID locationId, String traces) {
        PickingItem item = PickingItem.builder()
                .productId(productId)
                .requestedQuantity(new BigDecimal("1.000"))
                .pickedQuantity(new BigDecimal("1.000"))
                .locationId(locationId)
                .pickedTraces(traces)
                .build();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private static InventoryLot lot(
            UUID id, UUID productId, String number) {
        InventoryLot lot = InventoryLot.builder()
                .productId(productId)
                .lotNumber(number)
                .expirationDate(LocalDate.parse("2027-12-31"))
                .build();
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }
}
