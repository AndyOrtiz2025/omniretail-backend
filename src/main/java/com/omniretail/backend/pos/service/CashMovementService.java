package com.omniretail.backend.pos.service;

import com.omniretail.backend.pos.dto.CashMovementResponse;
import com.omniretail.backend.pos.dto.CreateCashMovementRequest;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class CashMovementService {
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard capability;
    private final CashShiftRepository shifts;
    private final CashMovementRepository movements;

    public CashMovementResponse create(CreateCashMovementRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        var shift = shifts.findOwnedByIdForUpdate(actor.tenantId(), actor.userId(), request.cashShiftId())
                .orElseThrow(() -> notFound());
        if (shift.getStatus() != CashShiftStatus.open) {
            throw new BusinessException(HttpStatus.CONFLICT, "CASH_SHIFT_ALREADY_CLOSED", "El turno de caja ya esta cerrado.");
        }
        CashMovement movement = CashMovement.builder().tenantId(actor.tenantId()).cashShiftId(shift.getId())
                .type(request.type()).amount(request.amount().setScale(2, RoundingMode.HALF_UP))
                .reason(request.reason().trim()).createdByUserId(actor.userId()).build();
        return CashMovementResponse.from(movements.save(movement));
    }

    @Transactional(readOnly = true)
    public List<CashMovementResponse> list(CreateCashMovementRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        shifts.findByTenantIdAndId(actor.tenantId(), request.cashShiftId())
                .filter(shift -> shift.getUserId().equals(actor.userId()))
                .orElseThrow(this::notFound);
        return movements.findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(actor.tenantId(), request.cashShiftId())
                .stream().map(CashMovementResponse::from).toList();
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado.");
    }
}
