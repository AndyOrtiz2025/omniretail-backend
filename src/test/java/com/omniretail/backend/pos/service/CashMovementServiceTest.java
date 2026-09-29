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
    @InjectMocks CashMovementService service;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID shiftId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(user, tenant, UserType.employee,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
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

    private CashShift shift(CashShiftStatus status) {
        CashShift shift = CashShift.builder().userId(user).status(status).build();
        ReflectionTestUtils.setField(shift, "id", shiftId);
        shift.setTenantId(tenant);
        return shift;
    }
}
