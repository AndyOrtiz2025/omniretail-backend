package com.omniretail.backend.inventory.service;

import com.omniretail.backend.inventory.dto.InventoryHistoricalTraceDetail;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementTrace;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.repository.InventoryLotRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementTraceRepository;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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
public class InventoryTraceabilityHistoryService {

    private final InventoryMovementTraceRepository traceRepository;
    private final InventoryLotRepository lotRepository;
    private final InventorySerialRepository serialRepository;

    @Transactional(readOnly = true)
    public Map<UUID, List<InventoryHistoricalTraceDetail>> expand(
            UUID tenantId, Collection<InventoryMovement> movements) {
        if (movements == null || movements.isEmpty()) return Map.of();
        List<UUID> movementIds = movements.stream().map(InventoryMovement::getId).toList();
        List<InventoryMovementTrace> traces = traceRepository
                .findByTenantIdAndMovementIdInOrderByMovementIdAscIdAsc(tenantId, movementIds);
        Map<UUID, List<InventoryMovementTrace>> tracesByMovement = traces.stream()
                .collect(Collectors.groupingBy(InventoryMovementTrace::getMovementId));

        List<UUID> lotIds = traces.stream()
                .map(InventoryMovementTrace::getLotId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<UUID> serialIds = traces.stream()
                .map(InventoryMovementTrace::getSerialId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, InventorySerial> serials = serialIds.isEmpty()
                ? Map.of()
                : serialRepository.findByTenantIdAndIdIn(tenantId, serialIds).stream()
                        .collect(Collectors.toMap(InventorySerial::getId, Function.identity()));
        List<UUID> serialLotIds = serials.values().stream()
                .map(InventorySerial::getLotId)
                .filter(Objects::nonNull)
                .toList();
        List<UUID> allLotIds = java.util.stream.Stream.concat(lotIds.stream(), serialLotIds.stream())
                .distinct()
                .toList();
        Map<UUID, InventoryLot> lots = allLotIds.isEmpty()
                ? Map.of()
                : lotRepository.findByTenantIdAndIdIn(tenantId, allLotIds).stream()
                        .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));

        Map<UUID, List<InventoryHistoricalTraceDetail>> result = new HashMap<>();
        for (InventoryMovement movement : movements) {
            List<InventoryMovementTrace> movementTraces =
                    tracesByMovement.getOrDefault(movement.getId(), List.of());
            if (movementTraces.isEmpty()) {
                result.put(movement.getId(), List.of());
                continue;
            }
            UUID locationId = movement.getType() == InventoryMovementType.out
                    ? movement.getFromLocationId()
                    : movement.getToLocationId();
            boolean serialTrace = movementTraces.stream()
                    .anyMatch(trace -> trace.getSerialId() != null);
            if (!serialTrace) {
                result.put(movement.getId(), movementTraces.stream()
                        .map(trace -> detail(
                                movement.getProductId(), locationId, trace.getLotId(), lots,
                                trace.getQuantity(), List.of()))
                        .sorted(detailComparator())
                        .toList());
                continue;
            }

            Map<UUID, List<InventorySerial>> byLot = new HashMap<>();
            for (InventoryMovementTrace trace : movementTraces) {
                InventorySerial serial = serials.get(trace.getSerialId());
                if (serial == null || !serial.getProductId().equals(movement.getProductId())) {
                    throw BusinessException.conflict(
                            "INVENTORY_TRACE_HISTORY_INCONSISTENT",
                            "El historial trazable del movimiento es inconsistente.");
                }
                byLot.computeIfAbsent(serial.getLotId(), ignored -> new ArrayList<>()).add(serial);
            }
            List<InventoryHistoricalTraceDetail> details = byLot.entrySet().stream()
                    .map(entry -> {
                        List<String> numbers = entry.getValue().stream()
                                .map(InventorySerial::getSerialNumber)
                                .sorted()
                                .toList();
                        return detail(
                                movement.getProductId(), locationId, entry.getKey(), lots,
                                BigDecimal.valueOf(numbers.size()), numbers);
                    })
                    .sorted(detailComparator())
                    .toList();
            result.put(movement.getId(), details);
        }
        return Map.copyOf(result);
    }

    private static InventoryHistoricalTraceDetail detail(
            UUID productId,
            UUID locationId,
            UUID lotId,
            Map<UUID, InventoryLot> lots,
            BigDecimal quantity,
            List<String> serialNumbers) {
        InventoryLot lot = lotId == null ? null : lots.get(lotId);
        if (lotId != null && lot == null) {
            throw BusinessException.conflict(
                    "INVENTORY_TRACE_HISTORY_INCONSISTENT",
                    "El lote del historial trazable ya no esta disponible.");
        }
        return new InventoryHistoricalTraceDetail(
                productId,
                locationId,
                lotId,
                lot == null ? null : lot.getLotNumber(),
                quantity,
                serialNumbers);
    }

    private static Comparator<InventoryHistoricalTraceDetail> detailComparator() {
        return Comparator.comparing(
                        InventoryHistoricalTraceDetail::productId)
                .thenComparing(
                        InventoryHistoricalTraceDetail::locationId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                        InventoryHistoricalTraceDetail::lotId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(detail -> detail.serialNumbers().isEmpty()
                        ? ""
                        : detail.serialNumbers().getFirst());
    }
}
