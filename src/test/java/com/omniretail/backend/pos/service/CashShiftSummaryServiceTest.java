package com.omniretail.backend.pos.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.pos.dto.CashMovementTotals;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CashShiftSummaryServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID SHIFT_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private CashShiftRepository cashShiftRepository;

    @Mock
    private CashMovementRepository cashMovementRepository;

    @Mock
    private BranchAccessResolver branchAccessResolver;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private TenantCapabilityGuard tenantCapabilityGuard;

    @Mock
    private AuthenticatedUser actor;

    @InjectMocks
    private CashShiftSummaryService service;

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(actor);
        when(actor.tenantId()).thenReturn(TENANT_ID);
        lenient().when(actor.userId()).thenReturn(USER_ID);
    }

    @Test
    void openShiftWithoutMovementsUsesOpeningAmountAsExpectedAmount() {
        CashShift shift = shift(CashShiftStatus.open, USER_ID, "100.00", null, null, null);
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.of(shift));
        when(cashMovementRepository.summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID))
                .thenReturn(totals("0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00"));

        var response = service.getSummary(SHIFT_ID);

        assertAll(
                () -> assertEquals(new BigDecimal("100.00"), response.expectedAmount()),
                () -> assertNull(response.closedAt()),
                () -> assertNull(response.countedAmount()),
                () -> assertNull(response.difference()));
    }

    @Test
    void openShiftCalculatesExpectedAmountAndMapsAllMovementClassifications() {
        CashShift shift = shift(CashShiftStatus.open, USER_ID, "100.00", null, null, null);
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.of(shift));
        when(cashMovementRepository.summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID))
                .thenReturn(totals("80.00", "25.00", "10.00", "4.00", "70.00", "6.00", "15.00"));

        var response = service.getSummary(SHIFT_ID);

        assertAll(
                () -> assertEquals(new BigDecimal("80.00"), response.cashIn()),
                () -> assertEquals(new BigDecimal("25.00"), response.cashOut()),
                () -> assertEquals(new BigDecimal("10.00"), response.manualCashIn()),
                () -> assertEquals(new BigDecimal("4.00"), response.manualCashOut()),
                () -> assertEquals(new BigDecimal("70.00"), response.salesCashIn()),
                () -> assertEquals(new BigDecimal("6.00"), response.voidCashOut()),
                () -> assertEquals(new BigDecimal("15.00"), response.returnCashOut()),
                () -> assertEquals(new BigDecimal("155.00"), response.expectedAmount()));
    }

    @Test
    void closedShiftUsesPersistedExpectedAmountInsteadOfRecalculation() {
        CashShift shift = shift(
                CashShiftStatus.closed, USER_ID, "100.00", "175.00", "175.00", "0.00");
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.of(shift));
        when(cashMovementRepository.summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID))
                .thenReturn(totals("100.00", "10.00", "0.00", "0.00", "100.00", "10.00", "0.00"));

        var response = service.getSummary(SHIFT_ID);

        assertEquals(new BigDecimal("175.00"), response.expectedAmount());
    }

    @Test
    void closedWithDifferenceReturnsPersistedCountAndDifference() {
        CashShift shift = shift(
                CashShiftStatus.closed_with_difference,
                USER_ID,
                "100.00",
                "175.00",
                "170.00",
                "-5.00");
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.of(shift));
        when(cashMovementRepository.summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID))
                .thenReturn(totals("75.00", "0.00", "0.00", "0.00", "75.00", "0.00", "0.00"));

        var response = service.getSummary(SHIFT_ID);

        assertAll(
                () -> assertEquals(CashShiftStatus.closed_with_difference, response.status()),
                () -> assertEquals(new BigDecimal("175.00"), response.expectedAmount()),
                () -> assertEquals(new BigDecimal("170.00"), response.countedAmount()),
                () -> assertEquals(new BigDecimal("-5.00"), response.difference()));
    }

    @Test
    void shiftFromAnotherTenantIsNotExposed() {
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getSummary(SHIFT_ID));

        verify(cashMovementRepository, never())
                .summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID);
    }

    @Test
    void shiftOutsideBranchScopeIsNotExposed() {
        CashShift shift = mock(CashShift.class);
        when(shift.getUserId()).thenReturn(OTHER_USER_ID);
        when(shift.getBranchId()).thenReturn(BRANCH_ID);
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.of(shift));
        when(branchAccessResolver.resolve(actor)).thenReturn(new BranchAccess(false, Set.of()));

        assertThrows(BusinessException.class, () -> service.getSummary(SHIFT_ID));

        verify(cashMovementRepository, never())
                .summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID);
    }

    @Test
    void userWithBranchAccessCanReadAnotherUsersShift() {
        CashShift shift = shift(CashShiftStatus.open, OTHER_USER_ID, "100.00", null, null, null);
        when(cashShiftRepository.findByTenantIdAndId(TENANT_ID, SHIFT_ID))
                .thenReturn(Optional.of(shift));
        when(branchAccessResolver.resolve(actor))
                .thenReturn(new BranchAccess(false, Set.of(BRANCH_ID)));
        when(cashMovementRepository.summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID))
                .thenReturn(totals("0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00"));

        service.getSummary(SHIFT_ID);

        verify(tenantCapabilityGuard)
                .ensureTenantCapability(TENANT_ID, SaasCapability.pos);
        verify(cashMovementRepository)
                .summarizeByTenantIdAndCashShiftId(TENANT_ID, SHIFT_ID);
    }

    private static CashShift shift(
            CashShiftStatus status,
            UUID ownerId,
            String openingAmount,
            String expectedAmount,
            String countedAmount,
            String difference) {
        CashShift shift = mock(CashShift.class);
        when(shift.getId()).thenReturn(SHIFT_ID);
        when(shift.getBranchId()).thenReturn(BRANCH_ID);
        when(shift.getUserId()).thenReturn(ownerId);
        when(shift.getRegisterCode()).thenReturn("POS-01");
        when(shift.getStatus()).thenReturn(status);
        when(shift.getOpenedAt()).thenReturn(Instant.parse("2026-09-29T12:00:00Z"));
        when(shift.getOpeningAmount()).thenReturn(new BigDecimal(openingAmount));
        if (status != CashShiftStatus.open) {
            when(shift.getClosedAt()).thenReturn(Instant.parse("2026-09-29T20:00:00Z"));
            when(shift.getExpectedAmount()).thenReturn(new BigDecimal(expectedAmount));
            when(shift.getCountedAmount()).thenReturn(new BigDecimal(countedAmount));
            when(shift.getDifference()).thenReturn(new BigDecimal(difference));
        }
        return shift;
    }

    private static CashMovementTotals totals(
            String cashIn,
            String cashOut,
            String manualCashIn,
            String manualCashOut,
            String salesCashIn,
            String voidCashOut,
            String returnCashOut) {
        return new CashMovementTotals(
                new BigDecimal(cashIn),
                new BigDecimal(cashOut),
                new BigDecimal(manualCashIn),
                new BigDecimal(manualCashOut),
                new BigDecimal(salesCashIn),
                new BigDecimal(voidCashOut),
                new BigDecimal(returnCashOut));
    }
}
