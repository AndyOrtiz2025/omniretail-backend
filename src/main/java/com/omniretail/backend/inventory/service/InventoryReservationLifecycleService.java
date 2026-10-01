package com.omniretail.backend.inventory.service;

import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class InventoryReservationLifecycleService {

    private final InventoryReservationRepository reservationRepository;
    private final InventoryStockService inventoryStockService;
    private final JsonMapper jsonMapper;

    @Transactional
    public InventoryReservation reserve(ReserveInventoryCommand command) {
        validate(command);
        if (reservationRepository.existsByTenantIdAndSourceTypeAndSourceLineId(
                command.tenantId(), command.sourceType(), command.sourceLineId())) {
            throw duplicateReservation();
        }

        InventoryBalance balance = inventoryStockService.reserveStock(
                command.tenantId(), command.branchId(), command.productId(), command.quantity());
        InventoryReservation reservation = InventoryReservation.builder()
                .branchId(command.branchId())
                .sourceType(command.sourceType())
                .sourceId(command.sourceId())
                .sourceLineId(command.sourceLineId())
                .orderId(command.orderId())
                .orderItemId(command.orderItemId())
                .productId(command.productId())
                .quantity(command.quantity())
                .allocations(allocationJson(balance.getId(), command.quantity()))
                .build();
        reservation.setTenantId(command.tenantId());
        try {
            return reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateReservation();
        }
    }

    @Transactional
    public InventoryReservation consume(UUID tenantId, UUID reservationId) {
        InventoryReservation reservation = requireLocked(tenantId, reservationId);
        if (reservation.getStatus() == InventoryReservationStatus.consumed) {
            return reservation;
        }
        if (reservation.getStatus() == InventoryReservationStatus.released) {
            throw invalidTransition("Una reserva liberada no puede consumirse.");
        }

        inventoryStockService.consumeReservedStock(
                tenantId,
                reservation.getBranchId(),
                reservation.getProductId(),
                reservation.getQuantity());
        reservation.setStatus(InventoryReservationStatus.consumed);
        return reservation;
    }

    @Transactional
    public InventoryReservation release(UUID tenantId, UUID reservationId) {
        InventoryReservation reservation = requireLocked(tenantId, reservationId);
        if (reservation.getStatus() == InventoryReservationStatus.released) {
            return reservation;
        }
        if (reservation.getStatus() == InventoryReservationStatus.consumed) {
            throw invalidTransition("Una reserva consumida no puede liberarse.");
        }

        inventoryStockService.releaseReservedStock(
                tenantId,
                reservation.getBranchId(),
                reservation.getProductId(),
                reservation.getQuantity());
        reservation.setStatus(InventoryReservationStatus.released);
        return reservation;
    }

    private InventoryReservation requireLocked(UUID tenantId, UUID reservationId) {
        if (tenantId == null || reservationId == null) {
            throw BusinessException.badRequest("Tenant y reserva son requeridos.");
        }
        return reservationRepository.findByTenantIdAndId(tenantId, reservationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "INVENTORY_RESERVATION_NOT_FOUND",
                        "Reserva de inventario no encontrada."));
    }

    private static void validate(ReserveInventoryCommand command) {
        if (command == null
                || command.tenantId() == null
                || command.branchId() == null
                || command.productId() == null
                || command.sourceType() == null
                || command.sourceId() == null
                || command.sourceLineId() == null
                || command.quantity() == null
                || command.quantity().signum() <= 0) {
            throw BusinessException.badRequest("Los datos de la reserva son invalidos.");
        }
        if (command.sourceType() == InventoryReservationSourceType.order
                && (!Objects.equals(command.sourceId(), command.orderId())
                        || !Objects.equals(command.sourceLineId(), command.orderItemId()))) {
            throw BusinessException.badRequest(
                    "La reserva de pedido no coincide con sus referencias de compatibilidad.");
        }
        if (command.sourceType() == InventoryReservationSourceType.transfer
                && (command.orderId() != null || command.orderItemId() != null)) {
            throw BusinessException.badRequest(
                    "Una reserva de transferencia no puede referenciar un pedido.");
        }
    }

    private String allocationJson(UUID balanceId, BigDecimal quantity) {
        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("id", UUID.randomUUID());
        allocation.put("balanceId", balanceId);
        allocation.put("locationId", null);
        allocation.put("reservedQuantity", quantity);
        allocation.put("consumedQuantity", BigDecimal.ZERO);
        return jsonMapper.writeValueAsString(List.of(allocation));
    }

    private static BusinessException duplicateReservation() {
        return BusinessException.conflict(
                "INVENTORY_RESERVATION_ALREADY_EXISTS",
                "La linea de origen ya posee una reserva de inventario.");
    }

    private static BusinessException invalidTransition(String message) {
        return BusinessException.conflict("INVALID_INVENTORY_RESERVATION_STATE", message);
    }
}
