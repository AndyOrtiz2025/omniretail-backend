package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.pos.dto.CloseCashShiftRequest;
import com.omniretail.backend.pos.dto.OpenCashShiftRequest;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CashShiftServiceTest {

    @Mock private CashShiftRepository shifts;
    @Mock private CashMovementRepository movements;
    @Mock private BranchRepository branches;
    @Mock private BranchAccessResolver access;
    @Mock private CurrentUser currentUser;
    @Mock private TenantCapabilityGuard capability;
    @InjectMocks private CashShiftService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID branchId = UUID.randomUUID();
    private final UUID shiftId = UUID.randomUUID();
    private final AuthenticatedUser actor = new AuthenticatedUser(
            userId, tenantId, UserType.employee, UUID.randomUUID(), branchId, UUID.randomUUID());

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(actor);
    }

    @Test
    void opensWithAuthenticatedIdentityAndNoInitialMovement() {
        allowBranch();
        when(shifts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.open(new OpenCashShiftRequest(branchId, " CAJA-1 ", BigDecimal.ZERO));
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.branchId()).isEqualTo(branchId);
        assertThat(result.registerCode()).isEqualTo("CAJA-1");
        assertThat(result.status()).isEqualTo(CashShiftStatus.open);
        assertThat(result.openedAt()).isNotNull();
        assertThat(result.closedAt()).isNull();
        verify(shifts).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                shift -> tenantId.equals(shift.getTenantId())));
        verify(capability).ensureTenantCapability(tenantId, SaasCapability.pos);
        verify(movements, never()).save(any());
    }

    @Test
    void rejectsExistingOpenShiftEvenForAnotherRegister() {
        allowBranch();
        when(shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(
                tenantId, branchId, userId, CashShiftStatus.open)).thenReturn(Optional.of(shift()));
        assertThatThrownBy(() -> service.open(new OpenCashShiftRequest(branchId, "OTHER", BigDecimal.TEN)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CASH_SHIFT_ALREADY_OPEN"));
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMissingOrCrossTenantBranch() {
        assertThatThrownBy(() -> service.open(new OpenCashShiftRequest(branchId, "A", BigDecimal.ZERO)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BRANCH_NOT_FOUND"));
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void rejectsBranchOutsideAssignedScope() {
        allowBranch();
        when(access.resolve(actor)).thenReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.open(new OpenCashShiftRequest(branchId, "A", BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class);
        verify(shifts, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @CsvSource({"125.00,0.00,closed", "120.00,-5.00,closed_with_difference", "130.00,5.00,closed_with_difference"})
    void closesUsingOpeningPlusEntriesMinusExits(String counted, String difference, CashShiftStatus status) {
        CashShift shift = allowClose();
        when(movements.findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(tenantId, shiftId))
                .thenReturn(List.of(
                        CashMovement.builder().type(CashMovementType.in).amount(new BigDecimal("40.00")).build(),
                        CashMovement.builder().type(CashMovementType.out).amount(new BigDecimal("15.00")).build()));
        when(shifts.saveAndFlush(shift)).thenReturn(shift);
        var result = service.close(new CloseCashShiftRequest(shiftId, new BigDecimal(counted)));
        assertThat(result.expectedAmount()).isEqualByComparingTo("125.00");
        assertThat(result.difference()).isEqualByComparingTo(difference);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.closedAt()).isAfterOrEqualTo(result.openedAt());
    }

    @Test
    void closesWithoutMovementsUsingOnlyOpeningAmount() {
        CashShift shift = allowClose();
        when(shifts.saveAndFlush(shift)).thenReturn(shift);
        var result = service.close(new CloseCashShiftRequest(shiftId, new BigDecimal("100.00")));
        assertThat(result.expectedAmount()).isEqualByComparingTo("100.00");
        assertThat(result.status()).isEqualTo(CashShiftStatus.closed);
    }

    @Test
    void cannotOverwriteAlreadyClosedShift() {
        CashShift shift = allowClose();
        shift.setStatus(CashShiftStatus.closed_with_difference);
        assertThatThrownBy(() -> service.close(new CloseCashShiftRequest(shiftId, BigDecimal.ZERO)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CASH_SHIFT_ALREADY_CLOSED"));
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMissingOrUnownedShift() {
        assertThatThrownBy(() -> service.close(new CloseCashShiftRequest(shiftId, BigDecimal.ZERO)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CASH_SHIFT_NOT_FOUND"));
        verify(shifts).findOwnedByIdForUpdate(tenantId, userId, shiftId);
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void cannotCloseShiftOutsideBranchScope() {
        allowClose();
        when(access.resolve(actor)).thenReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.close(new CloseCashShiftRequest(shiftId, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class);
        verify(shifts, never()).saveAndFlush(any());
    }

    private void allowBranch() {
        Branch branch = Branch.builder().build();
        ReflectionTestUtils.setField(branch, "id", branchId);
        when(branches.findByTenantIdAndId(tenantId, branchId)).thenReturn(Optional.of(branch));
        when(access.resolve(actor)).thenReturn(new BranchAccess(false, Set.of(branchId)));
    }

    private CashShift allowClose() {
        CashShift shift = shift();
        when(shifts.findOwnedByIdForUpdate(tenantId, userId, shiftId)).thenReturn(Optional.of(shift));
        when(access.resolve(actor)).thenReturn(new BranchAccess(false, Set.of(branchId)));
        return shift;
    }

    private CashShift shift() {
        CashShift shift = CashShift.builder().branchId(branchId).userId(userId).registerCode("A")
                .openedAt(Instant.now().minusSeconds(60)).openingAmount(new BigDecimal("100.00")).build();
        shift.setTenantId(tenantId);
        ReflectionTestUtils.setField(shift, "id", shiftId);
        return shift;
    }
}
