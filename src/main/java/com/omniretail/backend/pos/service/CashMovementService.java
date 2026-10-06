package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.pos.dto.CashMovementResponse;
import com.omniretail.backend.pos.dto.CreateCashMovementRequest;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final BranchAccessResolver branchAccessResolver;
    private final SaleRepository sales;

    public CashMovementResponse create(CreateCashMovementRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        var shift = shifts.findOwnedByIdForUpdate(actor.tenantId(), actor.userId(), request.cashShiftId())
                .filter(found -> branchAccessResolver.resolve(actor).allows(found.getBranchId()))
                .orElseThrow(this::notFound);
        if (shift.getStatus() != CashShiftStatus.open) {
            throw new BusinessException(HttpStatus.CONFLICT, "CASH_SHIFT_ALREADY_CLOSED", "El turno de caja ya esta cerrado.");
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (request.type() == CashMovementType.out) {
            BigDecimal currentCash = movements
                    .findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(actor.tenantId(), shift.getId())
                    .stream()
                    .map(movement -> movement.getType() == CashMovementType.in
                            ? movement.getAmount() : movement.getAmount().negate())
                    .reduce(shift.getOpeningAmount(), BigDecimal::add);
            if (amount.compareTo(currentCash) > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_CASH_BALANCE",
                        "Fondos insuficientes en la caja para realizar la salida.");
            }
        }
        CashMovement movement = CashMovement.builder()
                .tenantId(actor.tenantId()).cashShiftId(shift.getId()).type(request.type()).amount(amount)
                .reason(request.reason().trim()).createdByUserId(actor.userId()).build();
        return CashMovementResponse.from(movements.save(movement));
    }

    @Transactional(readOnly = true)
    public List<CashMovementResponse> listByShift(UUID cashShiftId) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        var shift = shifts.findByTenantIdAndId(actor.tenantId(), cashShiftId)
                .filter(found -> found.getUserId().equals(actor.userId())
                        || branchAccessResolver.resolve(actor).allows(found.getBranchId()))
                .orElseThrow(this::notFound);
        List<CashMovement> shiftMovements = movements
                .findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(actor.tenantId(), shift.getId());
        Set<UUID> saleIds = shiftMovements.stream()
                .filter(CashMovementService::referencesSale)
                .map(CashMovement::getReferenceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> saleNumbers = saleIds.isEmpty()
                ? Map.of()
                : sales.findByTenantIdAndIdIn(actor.tenantId(), saleIds).stream()
                        .collect(Collectors.toMap(
                                sale -> sale.getId(),
                                sale -> sale.getNumber(),
                                (first, second) -> first));
        return shiftMovements.stream()
                .map(movement -> CashMovementResponse.from(
                        movement,
                        referencesSale(movement) ? saleNumbers.get(movement.getReferenceId()) : null))
                .toList();
    }

    private static boolean referencesSale(CashMovement movement) {
        return "sale".equals(movement.getReferenceType())
                || "sale_void".equals(movement.getReferenceType());
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado.");
    }
}
