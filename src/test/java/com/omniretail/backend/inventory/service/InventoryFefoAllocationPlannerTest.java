package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryFefoAllocationPlannerTest {

    private final InventoryFefoAllocationPlanner planner = new InventoryFefoAllocationPlanner();
    private final LocalDate today = LocalDate.of(2026, 10, 19);

    @Test
    void allocatesAcrossLotsInFefoOrderWithDeterministicTieBreaks() {
        UUID later = UUID.randomUUID();
        UUID lotB = UUID.randomUUID();
        UUID lotA = UUID.randomUUID();

        InventoryFefoAllocationPlanner.Plan plan = planner.plan(
                today,
                quantity("6"),
                List.of(),
                List.of(
                        candidate(later, "LOT-LATER", today.plusDays(20), "10", "0"),
                        candidate(lotB, "LOT-B", today.plusDays(5), "3", "0"),
                        candidate(lotA, "LOT-A", today.plusDays(5), "3", "0")));

        assertThat(plan.incrementalAllocations())
                .extracting(
                        InventoryFefoAllocationPlanner.Allocation::lotId,
                        InventoryFefoAllocationPlanner.Allocation::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(lotA, quantity("3")),
                        org.assertj.core.groups.Tuple.tuple(lotB, quantity("3")));
        assertThat(plan.resultingAllocations()).isEqualTo(plan.incrementalAllocations());
    }

    @Test
    void preservesOwnAllocationAndSubtractsOtherReservationsFromTheIncrement() {
        UUID ownLot = UUID.randomUUID();
        UUID nextLot = UUID.randomUUID();

        InventoryFefoAllocationPlanner.Plan plan = planner.plan(
                today,
                quantity("7"),
                List.of(allocation(ownLot, "2")),
                List.of(
                        candidate(ownLot, "LOT-OWN", today.plusDays(2), "5", "4"),
                        candidate(nextLot, "LOT-NEXT", today.plusDays(3), "5", "0")));

        assertThat(plan.existingAllocations()).containsExactly(allocation(ownLot, "2"));
        assertThat(plan.incrementalAllocations())
                .containsExactly(allocation(ownLot, "1"), allocation(nextLot, "4"));
        assertThat(plan.resultingAllocations())
                .containsExactly(allocation(ownLot, "3"), allocation(nextLot, "4"));
    }

    @Test
    void excludesExpiredAndMissingExpirationCandidates() {
        UUID valid = UUID.randomUUID();
        InventoryFefoAllocationPlanner.Plan plan = planner.plan(
                today,
                quantity("2"),
                List.of(),
                List.of(
                        candidate(UUID.randomUUID(), "EXPIRED", today.minusDays(1), "10", "0"),
                        candidate(UUID.randomUUID(), "NO-DATE", null, "10", "0"),
                        candidate(valid, "VALID", today, "2", "0")));

        assertThat(plan.resultingAllocations()).containsExactly(allocation(valid, "2"));
    }

    @Test
    void rejectsInsufficientStockWithoutProducingAPartialPlan() {
        assertThatThrownBy(() -> planner.plan(
                        today,
                        quantity("3"),
                        List.of(),
                        List.of(candidate(
                                UUID.randomUUID(), "LOT", today.plusDays(1), "5", "3"))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_TRACEABLE_STOCK"));
    }

    @Test
    void rejectsADecreaseBelowAnExistingPhysicalSelection() {
        UUID lot = UUID.randomUUID();
        assertThatThrownBy(() -> planner.plan(
                        today,
                        quantity("1"),
                        List.of(allocation(lot, "2")),
                        List.of(candidate(lot, "LOT", today.plusDays(1), "5", "2"))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("TRACKING_QUANTITY_MISMATCH"));
    }

    private InventoryFefoAllocationPlanner.Candidate candidate(
            UUID lotId, String lotNumber, LocalDate expiration, String physical, String reserved) {
        return new InventoryFefoAllocationPlanner.Candidate(
                lotId, lotNumber, expiration, quantity(physical), quantity(reserved));
    }

    private static InventoryFefoAllocationPlanner.Allocation allocation(UUID lotId, String quantity) {
        return new InventoryFefoAllocationPlanner.Allocation(lotId, quantity(quantity));
    }

    private static BigDecimal quantity(String value) {
        return new BigDecimal(value).setScale(3);
    }
}
