package com.omniretail.backend.logistics.service;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.inventory.dto.InventoryPhysicalSelection;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.repository.InventoryLotRepository;
import com.omniretail.backend.inventory.service.InventoryPhysicalSelectionCodec;
import com.omniretail.backend.logistics.dto.PhysicalTraceSelectionResponse;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PickingTraceProjectionService {

    private final InventoryPhysicalSelectionCodec physicalSelectionCodec;
    private final InventoryLotRepository lotRepository;

    public List<PhysicalTraceSelectionResponse> project(
            UUID tenantId, PickingItem item, Product product) {
        List<InventoryPhysicalSelection> selections =
                physicalSelectionCodec.decode(item.getPickedTraces());
        physicalSelectionCodec.withoutLocation(selections, item.getLocationId());
        validateQuantities(item, product, selections);
        List<UUID> lotIds = selections.stream()
                .map(InventoryPhysicalSelection::lotId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, InventoryLot> lots = lotIds.isEmpty()
                ? Map.of()
                : lotRepository.findByTenantIdAndIdIn(tenantId, lotIds).stream()
                        .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
        return selections.stream()
                .map(selection -> response(selection, product, lots))
                .toList();
    }

    /** Proyecta varias líneas cargando todas las identidades de lote en una sola consulta. */
    public Map<UUID, List<PhysicalTraceSelectionResponse>> projectAll(
            UUID tenantId, List<PickingItem> items, Map<UUID, Product> products) {
        Map<UUID, List<InventoryPhysicalSelection>> selectionsByItem = new LinkedHashMap<>();
        List<UUID> lotIds = new java.util.ArrayList<>();
        for (PickingItem item : items) {
            Product product = products.get(item.getProductId());
            if (product == null) {
                throw inconsistentHistory();
            }
            List<InventoryPhysicalSelection> selections =
                    physicalSelectionCodec.decode(item.getPickedTraces());
            physicalSelectionCodec.withoutLocation(selections, item.getLocationId());
            validateQuantities(item, product, selections);
            selectionsByItem.put(item.getId(), selections);
            selections.stream()
                    .map(InventoryPhysicalSelection::lotId)
                    .filter(Objects::nonNull)
                    .forEach(lotIds::add);
        }

        List<UUID> distinctLotIds = lotIds.stream().distinct().toList();
        Map<UUID, InventoryLot> lots = distinctLotIds.isEmpty()
                ? Map.of()
                : lotRepository.findByTenantIdAndIdIn(tenantId, distinctLotIds).stream()
                        .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));

        Map<UUID, List<PhysicalTraceSelectionResponse>> result = new HashMap<>();
        for (PickingItem item : items) {
            Product product = products.get(item.getProductId());
            result.put(
                    item.getId(),
                    selectionsByItem.getOrDefault(item.getId(), List.of()).stream()
                            .map(selection -> response(selection, product, lots))
                            .toList());
        }
        return Map.copyOf(result);
    }

    private static void validateQuantities(
            PickingItem item,
            Product product,
            List<InventoryPhysicalSelection> selections) {
        if (selections.isEmpty()) {
            if (isTraceable(product) && item.getPickedQuantity().signum() > 0) {
                throw inconsistentHistory();
            }
            return;
        }
        BigDecimal selectedQuantity = selections.stream()
                .map(InventoryPhysicalSelection::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (selectedQuantity.compareTo(item.getPickedQuantity()) != 0) {
            throw inconsistentHistory();
        }
    }

    private static PhysicalTraceSelectionResponse response(
            InventoryPhysicalSelection selection,
            Product product,
            Map<UUID, InventoryLot> lots) {
        InventoryLot lot = selection.lotId() == null ? null : lots.get(selection.lotId());
        if (selection.lotId() != null
                && (lot == null || !lot.getProductId().equals(product.getId()))) {
            throw inconsistentHistory();
        }
        return new PhysicalTraceSelectionResponse(
                selection.locationId(),
                selection.lotId(),
                lot == null ? null : lot.getLotNumber(),
                lot == null ? null : lot.getExpirationDate(),
                selection.quantity(),
                List.copyOf(selection.serialNumbers()));
    }

    private static boolean isTraceable(Product product) {
        return product != null
                && product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock())
                && (Boolean.TRUE.equals(product.getTrackingLot())
                        || Boolean.TRUE.equals(product.getTrackingSerial()));
    }

    private static BusinessException inconsistentHistory() {
        return BusinessException.conflict(
                "PICKING_TRACE_HISTORY_INCONSISTENT",
                "La seleccion fisica persistida del Picking es inconsistente.");
    }
}
