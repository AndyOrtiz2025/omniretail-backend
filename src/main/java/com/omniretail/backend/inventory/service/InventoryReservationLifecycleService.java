package com.omniretail.backend.inventory.service;

import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class InventoryReservationLifecycleService {

    private static final TypeReference<List<ReservationAllocation>> ALLOCATIONS_TYPE = new TypeReference<>() {};

    private final InventoryReservationRepository reservationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryStockService inventoryStockService;
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;

    @Transactional
    public InventoryReservation reserve(ReserveInventoryCommand command) {
        validate(command);
        if (reservationRepository.existsByTenantIdAndSourceTypeAndSourceLineIdAndProductId(
                command.tenantId(), command.sourceType(), command.sourceLineId(), command.productId())) {
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
                .allocations(allocationJson(balance.getId(), balance.getLocationId(), command.quantity()))
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
        InventoryReservation snapshot = requireSnapshot(tenantId, reservationId);
        ReservationBalanceLocks locks = lockSourceBalances(tenantId, snapshot);
        return consume(tenantId, reservationId, locks);
    }

    @Transactional
    public InventoryReservation consume(
            UUID tenantId, UUID reservationId, ReservationBalanceLocks locks) {
        InventoryReservation reservation = requireLocks(tenantId, reservationId, locks);
        if (reservation.getStatus() == InventoryReservationStatus.consumed) {
            return reservation;
        }
        if (reservation.getStatus() == InventoryReservationStatus.released) {
            throw invalidTransition("Una reserva liberada no puede consumirse.");
        }

        consumeAllocations(tenantId, reservation, locks);
        reservation.setStatus(InventoryReservationStatus.consumed);
        return reservation;
    }

    private void consumeAllocations(
            UUID tenantId,
            InventoryReservation reservation,
            ReservationBalanceLocks locks) {
        List<ReservationAllocation> allocations = jsonMapper.readValue(reservation.getAllocations(), ALLOCATIONS_TYPE);
        if (allocations.isEmpty()) {
            inventoryStockService.consumeReservedStock(
                    tenantId,
                    reservation.getBranchId(),
                    reservation.getProductId(),
                    locks.requireDefault(reservation.getBranchId(), reservation.getProductId()),
                    reservation.getQuantity());
            return;
        }
        if (allocations.stream().anyMatch(a -> a.balanceId() == null || a.reservedQuantity() == null
                || a.reservedQuantity().signum() <= 0)) {
            throw invalidTransition("La reserva no contiene una asignacion de balance valida.");
        }
        BigDecimal total = allocations.stream().map(ReservationAllocation::reservedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(reservation.getQuantity()) != 0) {
            throw invalidTransition("Las asignaciones no coinciden con la cantidad reservada.");
        }
        List<ReservationAllocation> consumedAllocations = allocations.stream()
                .sorted(Comparator.comparing(ReservationAllocation::balanceId))
                .toList();
        for (ReservationAllocation allocation : consumedAllocations) {
            inventoryStockService.consumeReservedStock(
                    tenantId,
                    reservation.getBranchId(),
                    reservation.getProductId(),
                    locks.require(allocation.balanceId()),
                    allocation.reservedQuantity());
        }
        reservation.setAllocations(jsonMapper.writeValueAsString(consumedAllocations.stream()
                .map(allocation -> new ReservationAllocation(
                        allocation.id(), allocation.balanceId(), allocation.locationId(),
                        allocation.reservedQuantity(), allocation.reservedQuantity()))
                .toList()));
    }

    @Transactional
    public InventoryReservation release(UUID tenantId, UUID reservationId) {
        InventoryReservation snapshot = requireSnapshot(tenantId, reservationId);
        ReservationBalanceLocks locks = lockSourceBalances(tenantId, snapshot);
        return release(tenantId, reservationId, locks);
    }

    @Transactional
    public InventoryReservation release(
            UUID tenantId, UUID reservationId, ReservationBalanceLocks locks) {
        InventoryReservation reservation = requireLocks(tenantId, reservationId, locks);
        if (reservation.getStatus() == InventoryReservationStatus.released) {
            return reservation;
        }
        if (reservation.getStatus() == InventoryReservationStatus.consumed) {
            throw invalidTransition("Una reserva consumida no puede liberarse.");
        }

        releaseAllocations(tenantId, reservation, locks);
        reservation.setStatus(InventoryReservationStatus.released);
        return reservation;
    }

    private void releaseAllocations(
            UUID tenantId,
            InventoryReservation reservation,
            ReservationBalanceLocks locks) {
        List<ReservationAllocation> allocations = jsonMapper.readValue(reservation.getAllocations(), ALLOCATIONS_TYPE);
        if (allocations.isEmpty()) {
            inventoryStockService.releaseReservedStock(
                    tenantId,
                    reservation.getBranchId(),
                    reservation.getProductId(),
                    locks.requireDefault(reservation.getBranchId(), reservation.getProductId()),
                    reservation.getQuantity());
            return;
        }
        if (allocations.stream().anyMatch(allocation -> allocation.balanceId() == null)) {
            throw invalidTransition("La reserva no contiene un balance válido.");
        }
        for (ReservationAllocation allocation : allocations.stream()
                .sorted(Comparator.comparing(ReservationAllocation::balanceId)).toList()) {
            if (allocation.reservedQuantity() == null || allocation.reservedQuantity().signum() < 0) {
                throw invalidTransition("La reserva no contiene una cantidad reservada válida.");
            }
            BigDecimal consumed = allocation.consumedQuantity() == null
                    ? BigDecimal.ZERO : allocation.consumedQuantity();
            BigDecimal unconsumed = allocation.reservedQuantity().subtract(consumed);
            if (unconsumed.signum() < 0) {
                throw invalidTransition("La cantidad consumida excede la cantidad reservada.");
            }
            if (unconsumed.signum() > 0) {
                inventoryStockService.releaseReservedStock(
                        tenantId, reservation.getBranchId(), reservation.getProductId(),
                        locks.require(allocation.balanceId()), unconsumed);
            }
        }
    }

    /**
     * Bloquea todos los balances generales y despues todas las reservas antes de cualquier
     * balance de lote o serie. Cada fila se adquiere individualmente por UUID ascendente;
     * el orden efectivo no depende del plan de ejecucion de un SELECT batch.
     */
    @Transactional
    public ReservationBalanceLocks lockBalances(
            UUID tenantId, Collection<InventoryReservation> reservations) {
        if (tenantId == null || reservations == null) {
            throw BusinessException.badRequest("Tenant y reservas son requeridos.");
        }
        List<InventoryReservation> scoped = reservations.stream().toList();
        TreeSet<UUID> balanceIds = new TreeSet<>();
        for (InventoryReservation reservation : scoped) {
            requireReservationScope(tenantId, reservation);
            List<ReservationAllocation> allocations = allocations(reservation);
            if (allocations.isEmpty()) {
                balanceIds.add(requireDefaultBalanceId(reservation));
            } else {
                for (ReservationAllocation allocation : allocations) {
                    if (allocation.balanceId() == null) {
                        throw invalidTransition("La reserva no contiene un balance valido.");
                    }
                    balanceIds.add(allocation.balanceId());
                }
            }
        }
        if (balanceIds.isEmpty()) {
            return new ReservationBalanceLocks(tenantId, Map.of(), Map.of());
        }
        List<InventoryBalance> locked = balanceIds.stream()
                .map(balanceId -> balanceRepository.findByTenantIdAndId(tenantId, balanceId)
                        .orElseThrow(() -> invalidTransition(
                                "La reserva no coincide con el balance de inventario.")))
                .toList();
        if (locked.size() != balanceIds.size()) {
            throw invalidTransition("La reserva no coincide con el balance de inventario.");
        }
        Map<UUID, InventoryBalance> byId = locked.stream()
                .collect(Collectors.toMap(InventoryBalance::getId, Function.identity()));
        Map<UUID, InventoryReservation> lockedReservations = scoped.stream()
                .map(InventoryReservation::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(reservationId -> {
                    InventoryReservation reservation = reservationRepository
                            .findByTenantIdAndId(tenantId, reservationId)
                            .orElseThrow(InventoryReservationLifecycleService::reservationNotFound);
                    // Puede estar administrada como snapshot desde antes de esperar los balances.
                    entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
                    return reservation;
                })
                .collect(Collectors.toMap(InventoryReservation::getId, Function.identity()));
        if (lockedReservations.size() != scoped.size()) {
            throw invalidTransition("Una o mas reservas no tienen una identidad valida.");
        }
        ReservationBalanceLocks locks = new ReservationBalanceLocks(
                tenantId, byId, lockedReservations);
        for (InventoryReservation reservation : lockedReservations.values()) {
            validateReservationBalances(reservation, locks);
        }
        return locks;
    }

    private ReservationBalanceLocks lockSourceBalances(
            UUID tenantId, InventoryReservation reservation) {
        List<InventoryReservation> sourceReservations = reservationRepository
                .findByTenantIdAndSourceTypeAndSourceId(
                        tenantId, reservation.getSourceType(), reservation.getSourceId());
        return lockBalances(tenantId, sourceReservations);
    }

    private void validateReservationBalances(
            InventoryReservation reservation, ReservationBalanceLocks locks) {
        List<ReservationAllocation> allocations = allocations(reservation);
        if (allocations.isEmpty()) {
            locks.requireDefault(reservation.getBranchId(), reservation.getProductId());
            return;
        }
        for (ReservationAllocation allocation : allocations) {
            InventoryBalance balance = locks.require(allocation.balanceId());
            if (!balance.getBranchId().equals(reservation.getBranchId())
                    || !balance.getProductId().equals(reservation.getProductId())) {
                throw invalidTransition("La reserva no coincide con el balance de inventario.");
            }
        }
    }

    private InventoryReservation requireLocks(
            UUID tenantId,
            UUID reservationId,
            ReservationBalanceLocks locks) {
        if (locks == null || !tenantId.equals(locks.tenantId())) {
            throw invalidTransition("Los balances bloqueados no pertenecen al tenant de la reserva.");
        }
        InventoryReservation reservation = locks.requireReservation(reservationId);
        validateReservationBalances(reservation, locks);
        return reservation;
    }

    private UUID requireDefaultBalanceId(InventoryReservation reservation) {
        return balanceRepository.findDefaultBalanceId(
                        reservation.getTenantId(),
                        reservation.getBranchId(),
                        reservation.getProductId())
                .orElseThrow(() -> invalidTransition(
                        "La reserva no coincide con el balance de inventario."));
    }

    private List<ReservationAllocation> allocations(InventoryReservation reservation) {
        List<ReservationAllocation> allocations =
                jsonMapper.readValue(reservation.getAllocations(), ALLOCATIONS_TYPE);
        return allocations == null ? List.of() : allocations;
    }

    private static void requireReservationScope(UUID tenantId, InventoryReservation reservation) {
        if (reservation == null || !tenantId.equals(reservation.getTenantId())) {
            throw invalidTransition("La reserva no pertenece al tenant indicado.");
        }
    }

    private InventoryReservation requireSnapshot(UUID tenantId, UUID reservationId) {
        if (tenantId == null || reservationId == null) {
            throw BusinessException.badRequest("Tenant y reserva son requeridos.");
        }
        return reservationRepository.findSnapshotByTenantIdAndId(tenantId, reservationId)
                .orElseThrow(InventoryReservationLifecycleService::reservationNotFound);
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
                        || command.orderItemId() == null
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

    /** Registra la ubicacion real del balance reservado (null solo para el balance NULL heredado). */
    private String allocationJson(UUID balanceId, UUID locationId, BigDecimal quantity) {
        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("id", UUID.randomUUID());
        allocation.put("balanceId", balanceId);
        allocation.put("locationId", locationId);
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

    private static BusinessException reservationNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "INVENTORY_RESERVATION_NOT_FOUND",
                "Reserva de inventario no encontrada.");
    }

    public record ReservationBalanceLocks(
            UUID tenantId,
            Map<UUID, InventoryBalance> balances,
            Map<UUID, InventoryReservation> reservations) {

        public ReservationBalanceLocks {
            balances = Map.copyOf(balances);
            reservations = Map.copyOf(reservations);
        }

        public InventoryBalance require(UUID balanceId) {
            InventoryBalance balance = balances.get(balanceId);
            if (balance == null) {
                throw invalidTransition("La reserva no coincide con el balance de inventario.");
            }
            return balance;
        }

        public InventoryBalance requireDefault(UUID branchId, UUID productId) {
            return balances.values().stream()
                    .filter(balance -> balance.getBranchId().equals(branchId)
                            && balance.getProductId().equals(productId)
                            && balance.getLocationId() == null)
                    .findFirst()
                    .orElseThrow(() -> invalidTransition(
                            "La reserva no coincide con el balance de inventario."));
        }

        public InventoryReservation requireReservation(UUID reservationId) {
            InventoryReservation reservation = reservations.get(reservationId);
            if (reservation == null) {
                throw invalidTransition("La reserva no fue bloqueada para esta operacion.");
            }
            return reservation;
        }
    }

    private record ReservationAllocation(UUID id, UUID balanceId, UUID locationId,
            BigDecimal reservedQuantity, BigDecimal consumedQuantity) {}
}
