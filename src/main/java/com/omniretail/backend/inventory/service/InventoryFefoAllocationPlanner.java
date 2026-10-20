package com.omniretail.backend.inventory.service;

import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Pure FEFO planning shared by read-only previews and transactional allocation. */
@Component
public class InventoryFefoAllocationPlanner {

    public Plan plan(
            LocalDate businessDate,
            BigDecimal targetQuantity,
            List<Allocation> existingAllocations,
            List<Candidate> candidates) {
        if (targetQuantity == null || targetQuantity.signum() < 0) {
            throw BusinessException.badRequest("La cantidad objetivo FEFO no es valida.");
        }

        Map<UUID, BigDecimal> existingByLot = quantitiesByLot(existingAllocations);
        BigDecimal existingTotal = total(existingByLot);
        if (targetQuantity.compareTo(existingTotal) < 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La cantidad FEFO no puede ser menor que la seleccion fisica existente.");
        }

        Map<UUID, Candidate> candidateByLot = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidateByLot.put(candidate.lotId(), candidate));
        for (Map.Entry<UUID, BigDecimal> existing : existingByLot.entrySet()) {
            Candidate candidate = candidateByLot.get(existing.getKey());
            if (candidate == null
                    || candidate.expirationDate() == null
                    || candidate.expirationDate().isBefore(businessDate)) {
                throw BusinessException.conflict(
                        "LOT_EXPIRED", "Una seleccion FEFO existente ya no corresponde a un lote vigente.");
            }
            if (candidate.physicalQuantity().compareTo(existing.getValue()) < 0
                    || candidate.reservedQuantity().compareTo(existing.getValue()) < 0) {
                throw insufficientTraceableStock();
            }
        }

        List<Candidate> orderedCandidates = candidates.stream()
                .filter(candidate -> candidate.expirationDate() != null)
                .filter(candidate -> !candidate.expirationDate().isBefore(businessDate))
                .sorted(Comparator.comparing(Candidate::expirationDate)
                        .thenComparing(Candidate::lotNumber)
                        .thenComparing(Candidate::lotId))
                .toList();
        Map<UUID, BigDecimal> incrementalByLot = new LinkedHashMap<>();
        Map<UUID, BigDecimal> resultingByLot = new LinkedHashMap<>(existingByLot);
        BigDecimal remaining = targetQuantity.subtract(existingTotal);
        for (Candidate candidate : orderedCandidates) {
            if (remaining.signum() == 0) break;
            BigDecimal available = candidate.physicalQuantity()
                    .subtract(candidate.reservedQuantity())
                    .max(BigDecimal.ZERO);
            BigDecimal assigned = available.min(remaining);
            if (assigned.signum() > 0) {
                incrementalByLot.merge(candidate.lotId(), assigned, BigDecimal::add);
                resultingByLot.merge(candidate.lotId(), assigned, BigDecimal::add);
                remaining = remaining.subtract(assigned);
            }
        }
        if (remaining.signum() > 0) throw insufficientTraceableStock();

        return new Plan(
                allocations(orderedCandidates, existingByLot),
                allocations(orderedCandidates, incrementalByLot),
                allocations(orderedCandidates, resultingByLot));
    }

    private static Map<UUID, BigDecimal> quantitiesByLot(List<Allocation> allocations) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        if (allocations == null) return result;
        for (Allocation allocation : allocations) {
            if (allocation == null
                    || allocation.lotId() == null
                    || allocation.quantity() == null
                    || allocation.quantity().signum() <= 0) {
                throw BusinessException.conflict(
                        "PICKING_TRACE_HISTORY_INCONSISTENT",
                        "La seleccion fisica persistida del Picking es inconsistente.");
            }
            result.merge(allocation.lotId(), allocation.quantity(), BigDecimal::add);
        }
        return result;
    }

    private static BigDecimal total(Map<UUID, BigDecimal> quantities) {
        return quantities.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<Allocation> allocations(
            List<Candidate> orderedCandidates, Map<UUID, BigDecimal> quantities) {
        List<Allocation> result = new ArrayList<>();
        for (Candidate candidate : orderedCandidates) {
            BigDecimal quantity = quantities.get(candidate.lotId());
            if (quantity != null && quantity.signum() > 0) {
                result.add(new Allocation(candidate.lotId(), quantity));
            }
        }
        return List.copyOf(result);
    }

    private static BusinessException insufficientTraceableStock() {
        return BusinessException.conflict(
                "INSUFFICIENT_TRACEABLE_STOCK",
                "La existencia fisica trazable no es suficiente para completar la seleccion.");
    }

    public record Candidate(
            UUID lotId,
            String lotNumber,
            LocalDate expirationDate,
            BigDecimal physicalQuantity,
            BigDecimal reservedQuantity) {}

    public record Allocation(UUID lotId, BigDecimal quantity) {}

    public record Plan(
            List<Allocation> existingAllocations,
            List<Allocation> incrementalAllocations,
            List<Allocation> resultingAllocations) {}
}
