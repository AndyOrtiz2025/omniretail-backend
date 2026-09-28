package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CashShiftAdminService {

    private final CashShiftRepository cashShiftRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;

    // Igual que GetCashShiftsService.ts: solo turnos de sucursales dentro del alcance del usuario,
    // más recientes primero (openedAt descendente).
    public List<CashShiftResponse> listCashShifts(CashShiftStatus status, UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (branchId != null && !access.allows(branchId)) {
            return List.of();
        }

        List<CashShift> shifts;
        if (status != null && branchId != null) {
            shifts = cashShiftRepository.findByTenantIdAndBranchIdAndStatusOrderByOpenedAtDesc(
                    tenantId, branchId, status);
        } else if (status != null) {
            shifts = cashShiftRepository.findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, status);
        } else if (branchId != null) {
            shifts = cashShiftRepository.findByTenantIdAndBranchIdOrderByOpenedAtDesc(tenantId, branchId);
        } else {
            shifts = cashShiftRepository.findByTenantIdOrderByOpenedAtDesc(tenantId);
        }
        return shifts.stream()
                .filter(shift -> access.allows(shift.getBranchId()))
                .map(CashShiftResponse::from)
                .toList();
    }

    // Un turno fuera del alcance responde igual que uno inexistente, para no revelar que existe.
    public CashShiftResponse getCashShiftById(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        BranchAccess access = branchAccessResolver.resolve(actor);
        CashShift shift = cashShiftRepository
                .findByTenantIdAndId(actor.tenantId(), id)
                .filter(found -> access.allows(found.getBranchId()))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        return CashShiftResponse.from(shift);
    }
}
