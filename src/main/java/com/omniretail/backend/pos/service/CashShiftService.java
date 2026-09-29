package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.pos.dto.CloseCashShiftRequest;
import com.omniretail.backend.pos.dto.OpenCashShiftRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class CashShiftService {

    private final CashShiftRepository cashShiftRepository;
    private final CashMovementRepository cashMovementRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;

    public CashShiftResponse open(OpenCashShiftRequest request) {
        AuthenticatedUser actor = requireActor();
        branchRepository.findByTenantIdAndId(actor.tenantId(), request.branchId())
                .filter(branch -> branchAccessResolver.resolve(actor).allows(branch.getId()))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (cashShiftRepository.findByTenantIdAndBranchIdAndUserIdAndStatus(
                actor.tenantId(), request.branchId(), actor.userId(), CashShiftStatus.open).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "CASH_SHIFT_ALREADY_OPEN", "Ya tienes un turno abierto en esta sucursal.");
        }
        CashShift shift = CashShift.builder()
                .branchId(request.branchId())
                .userId(actor.userId())
                .registerCode(request.registerCode().trim())
                .status(CashShiftStatus.open)
                .openedAt(Instant.now())
                .openingAmount(request.openingAmount())
                .build();
        shift.setTenantId(actor.tenantId());
        // El indice parcial existente resuelve aperturas concurrentes; el handler devuelve 409.
        return CashShiftResponse.from(cashShiftRepository.saveAndFlush(shift));
    }

    public CashShiftResponse close(CloseCashShiftRequest request) {
        AuthenticatedUser actor = requireActor();
        CashShift shift = cashShiftRepository.findOwnedByIdForUpdate(
                        actor.tenantId(), actor.userId(), request.cashShiftId())
                .filter(found -> branchAccessResolver.resolve(actor).allows(found.getBranchId()))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        if (shift.getStatus() != CashShiftStatus.open) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "CASH_SHIFT_ALREADY_CLOSED", "El turno de caja ya esta cerrado.");
        }
        // El saldo inicial se suma una sola vez. El libro de caja contiene entradas y salidas.
        BigDecimal expected = cashMovementRepository
                .findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(actor.tenantId(), shift.getId())
                .stream()
                .map(movement -> movement.getType() == CashMovementType.in
                        ? movement.getAmount() : movement.getAmount().negate())
                .reduce(shift.getOpeningAmount(), BigDecimal::add);
        BigDecimal difference = request.countedAmount().subtract(expected);
        shift.setExpectedAmount(expected);
        shift.setCountedAmount(request.countedAmount());
        shift.setDifference(difference);
        shift.setClosedAt(Instant.now());
        shift.setStatus(difference.signum() == 0
                ? CashShiftStatus.closed : CashShiftStatus.closed_with_difference);
        return CashShiftResponse.from(cashShiftRepository.saveAndFlush(shift));
    }

    private AuthenticatedUser requireActor() {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        return actor;
    }
}
