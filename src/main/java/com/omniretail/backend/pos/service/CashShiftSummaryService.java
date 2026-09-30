package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.pos.dto.CashMovementTotals;
import com.omniretail.backend.pos.dto.CashShiftSummaryResponse;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CashShiftSummaryService {

    private final CashShiftRepository cashShiftRepository;
    private final CashMovementRepository cashMovementRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;

    public CashShiftSummaryResponse getSummary(UUID cashShiftId) {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);

        CashShift shift = cashShiftRepository.findByTenantIdAndId(actor.tenantId(), cashShiftId)
                .filter(found -> found.getUserId().equals(actor.userId())
                        || branchAccessResolver.resolve(actor).allows(found.getBranchId()))
                .orElseThrow(this::notFound);

        CashMovementTotals totals = cashMovementRepository.summarizeByTenantIdAndCashShiftId(
                actor.tenantId(), shift.getId());
        BigDecimal expectedAmount = shift.getStatus() == CashShiftStatus.open
                ? shift.getOpeningAmount().add(totals.cashIn()).subtract(totals.cashOut())
                : shift.getExpectedAmount();

        return new CashShiftSummaryResponse(
                shift.getId(),
                shift.getBranchId(),
                shift.getUserId(),
                shift.getRegisterCode(),
                shift.getStatus(),
                shift.getOpenedAt(),
                shift.getClosedAt(),
                shift.getOpeningAmount(),
                totals.cashIn(),
                totals.cashOut(),
                totals.manualCashIn(),
                totals.manualCashOut(),
                totals.salesCashIn(),
                totals.voidCashOut(),
                totals.returnCashOut(),
                expectedAmount,
                shift.getCountedAmount(),
                shift.getDifference());
    }

    private BusinessException notFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado.");
    }
}
