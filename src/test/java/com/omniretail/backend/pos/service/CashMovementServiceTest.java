package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.omniretail.backend.pos.dto.CreateCashMovementRequest;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.entity.UserType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CashMovementServiceTest {
    @Mock CurrentUser currentUser;
    @Mock TenantCapabilityGuard capability;
    @Mock CashShiftRepository shifts;
    @Mock CashMovementRepository movements;
    @Mock BranchAccessResolver branchAccessResolver;
    @InjectMocks CashMovementService service;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID shiftId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(user, tenant, UserType.employee,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        lenient().when(branchAccessResolver.resolve(any())).thenReturn(new BranchAccessResolver.BranchAccess(false, java.util.Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111"))));
    }

    @Test
    void createsInMovementOnOpenShift() {
        CashShift shift = shift(CashShiftStatus.open);
        when(shifts.findOwnedByIdForUpdate(tenant, user, shiftId)).thenReturn(Optional.of(shift));
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.create(new CreateCashMovementRequest(shiftId, CashMovementType.in,
                new BigDecimal("12.345"), "  Entrada manual  "));
        assertThat(result.amount()).isEqualByComparingTo("12.35");
        assertThat(result.reason()).isEqualTo("Entrada manual");
        verify(movements).save(any());
    }

    @Test
    void rejectsClosedShift() {
        when(shifts.findOwnedByIdForUpdate(tenant, user, shiftId)).thenReturn(Optional.of(shift(CashShiftStatus.closed)));
        assertThatThrownBy(() -> service.create(new CreateCashMovementRequest(shiftId, CashMovementType.out,
                BigDecimal.ONE, "Salida"))).isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CASH_SHIFT_ALREADY_CLOSED"));
        verifyNoInteractions(movements);
    }

    @Test
    void createsOutMovementWithFunds() {
        CashShift shift = shift(CashShiftStatus.open);
        when(shifts.findOwnedByIdForUpdate(tenant, user, shiftId)).thenReturn(Optional.of(shift));
        when(movements.findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(tenant, shiftId)).thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.create(new CreateCashMovementRequest(shiftId, CashMovementType.out,
                new BigDecimal("25.00"), "Salida"));
        assertThat(result.type()).isEqualTo(CashMovementType.out);
        verify(movements).save(any());
    }

    @Test
    void rejectsOutMovementWhenBalanceIsInsufficient() {
        CashShift shift = shift(CashShiftStatus.open);
        when(shifts.findOwnedByIdForUpdate(tenant, user, shiftId)).thenReturn(Optional.of(shift));
        when(movements.findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(tenant, shiftId)).thenReturn(java.util.List.of());
        assertThatThrownBy(() -> service.create(new CreateCashMovementRequest(shiftId, CashMovementType.out,
                new BigDecimal("100.01"), "Salida"))).isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_CASH_BALANCE"));
        verify(movements, never()).save(any());
    }

    @Test
    void ownerCanListShiftMovements() {
        CashShift shift = shift(CashShiftStatus.open);
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.of(shift));
        when(movements.findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(tenant, shiftId)).thenReturn(java.util.List.of());
        assertThat(service.listByShift(shiftId)).isEmpty();
    }

    @Test
    void supervisorWithBranchAccessCanListAnotherCashierShift() {
        UUID cashier = UUID.randomUUID();
        CashShift shift = shift(CashShiftStatus.open);
        shift.setUserId(cashier);
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.of(shift));
        when(movements.findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(tenant, shiftId)).thenReturn(java.util.List.of());
        assertThat(service.listByShift(shiftId)).isEmpty();
        verify(movements).findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(tenant, shiftId);
    }

    private CashShift shift(CashShiftStatus status) {
        CashShift shift = CashShift.builder().userId(user).branchId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .openingAmount(new BigDecimal("100.00")).status(status).build();
        ReflectionTestUtils.setField(shift, "id", shiftId);
        shift.setTenantId(tenant);
        return shift;
    }
}
