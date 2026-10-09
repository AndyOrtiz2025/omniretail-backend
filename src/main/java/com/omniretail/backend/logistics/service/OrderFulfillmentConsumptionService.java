package com.omniretail.backend.logistics.service;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.InventoryPhysicalSelection;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryPhysicalSelectionCodec;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService.ReservationBalanceLocks;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Consumo fisico comun de las reservas asociadas a un pedido completado. */
@Service
@RequiredArgsConstructor
public class OrderFulfillmentConsumptionService {

    private final InventoryReservationRepository reservations;
    private final InventoryReservationLifecycleService reservationLifecycle;
    private final InventoryTraceabilityMutationService traceabilityMutation;
    private final InventoryPhysicalSelectionCodec physicalSelectionCodec;
    private final InventoryMovementRepository movements;
    private final ProductRepository products;
    private final PickingOrderRepository pickingOrders;
    private final PickingItemRepository pickingItems;
    private final JsonMapper jsonMapper;

    @Transactional
    public void consumeOrder(
            UUID tenantId,
            UUID branchId,
            UUID orderId,
            UUID performedByUserId,
            String reason,
            String referenceType,
            UUID referenceId) {
        consumePrepared(
                prepareOrder(tenantId, branchId, orderId),
                performedByUserId,
                reason,
                referenceType,
                referenceId);
    }

    PreparedOrderConsumption prepareOrder(UUID tenantId, UUID branchId, UUID orderId) {
        List<InventoryReservation> active =
                reservations.findByTenantIdAndSourceTypeAndSourceIdAndStatus(
                        tenantId,
                        InventoryReservationSourceType.order,
                        orderId,
                        InventoryReservationStatus.active);
        if (active.isEmpty()) {
            throw conflict(
                    "INVENTORY_RESERVATION_NOT_ACTIVE",
                    "El pedido no tiene reservas activas.");
        }

        Map<UUID, Product> activeProducts = new HashMap<>();
        for (InventoryReservation reservation : active) {
            Product product = products.findByTenantIdAndId(tenantId, reservation.getProductId())
                    .orElseThrow(() -> notFound(
                            "PRODUCT_NOT_FOUND", "Producto no encontrado."));
            activeProducts.put(product.getId(), product);
            if (isTraceable(product)) {
                requirePickingSelection(
                        tenantId,
                        branchId,
                        orderId,
                        reservation.getSourceLineId(),
                        product,
                        reservation.getQuantity());
            }
        }
        return new PreparedOrderConsumption(
                tenantId, branchId, List.copyOf(active), Map.copyOf(activeProducts));
    }

    void consumePrepared(
            PreparedOrderConsumption prepared,
            UUID performedByUserId,
            String reason,
            String referenceType,
            UUID referenceId) {
        ReservationBalanceLocks lockedBalances = reservationLifecycle.lockBalances(
                prepared.tenantId(), prepared.reservations());
        for (InventoryReservation reservation : prepared.reservations()) {
            Product product = prepared.products().get(reservation.getProductId());
            List<Allocation> reservationAllocations = allocations(reservation, lockedBalances);
            BigDecimal aggregateBefore = isTraceable(product)
                    ? aggregateQuantity(lockedBalances, reservationAllocations)
                    : null;
            reservationLifecycle.consume(
                    prepared.tenantId(), reservation.getId(), lockedBalances);
            if (isTraceable(product)) {
                PickingSelection selection = requirePickingSelection(
                        prepared.tenantId(),
                        prepared.branchId(),
                        reservation.getSourceId(),
                        reservation.getSourceLineId(),
                        product,
                        reservation.getQuantity());
                traceabilityMutation.consumePhysicalReservation(
                        prepared.tenantId(),
                        prepared.branchId(),
                        product,
                        selection.item().getLocationId(),
                        reservation.getQuantity(),
                        selection.selections(),
                        InventorySerialStatus.CONSUMED,
                        aggregateBefore,
                        aggregateBefore.subtract(reservation.getQuantity()),
                        reason,
                        referenceType,
                        referenceId,
                        reservation.getSourceLineId(),
                        performedByUserId);
                continue;
            }
            for (Allocation allocation : reservationAllocations) {
                var balance = lockedBalances.require(allocation.balanceId());
                movements.save(InventoryMovement.builder()
                        .tenantId(prepared.tenantId())
                        .branchId(prepared.branchId())
                        .productId(reservation.getProductId())
                        .type(InventoryMovementType.out)
                        .reason(reason)
                        .quantity(allocation.quantity())
                        .quantityBefore(balance.getQuantity().add(allocation.quantity()))
                        .quantityAfter(balance.getQuantity())
                        .fromLocationId(balance.getLocationId())
                        .toLocationId(null)
                        .referenceType(referenceType)
                        .referenceId(referenceId)
                        .referenceLineId(reservation.getSourceLineId())
                        .performedByUserId(performedByUserId)
                        .build());
            }
        }
    }

    private PickingSelection requirePickingSelection(
            UUID tenantId,
            UUID branchId,
            UUID orderId,
            UUID sourceLineId,
            Product product,
            BigDecimal quantity) {
        PickingOrder picking = pickingOrders
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        tenantId, branchId, PickingSourceType.order, orderId)
                .filter(value -> value.getStatus() == PickingStatus.completed)
                .orElseThrow(() -> conflict(
                        "PICKING_TRACE_HISTORY_INCONSISTENT",
                        "El Picking trazable no esta completado."));
        PickingItem item = pickingItems.findByScopeAndSourceLine(
                        tenantId, branchId, picking.getId(), sourceLineId, product.getId())
                .orElseThrow(() -> conflict(
                        "PICKING_TRACE_HISTORY_INCONSISTENT",
                        "La linea trazable no pertenece al Picking completado."));
        List<InventoryPhysicalSelection> physical =
                physicalSelectionCodec.decode(item.getPickedTraces());
        List<InventoryTraceabilitySelection> selections =
                physicalSelectionCodec.withoutLocation(physical, item.getLocationId());
        BigDecimal selected = selections.stream()
                .map(InventoryTraceabilitySelection::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (item.getLocationId() == null
                || item.getPickedQuantity().compareTo(quantity) != 0
                || selected.compareTo(quantity) != 0) {
            throw conflict(
                    "PICKING_TRACE_HISTORY_INCONSISTENT",
                    "La seleccion fisica no coincide con la reserva despachada.");
        }
        return new PickingSelection(item, selections);
    }

    private BigDecimal aggregateQuantity(
            ReservationBalanceLocks lockedBalances, List<Allocation> allocations) {
        return allocations.stream()
                .map(allocation -> lockedBalances.require(allocation.balanceId()).getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Allocation> allocations(
            InventoryReservation reservation, ReservationBalanceLocks lockedBalances) {
        JsonNode root = jsonMapper.readTree(reservation.getAllocations());
        if (!root.isArray() || root.isEmpty()) {
            UUID balanceId = lockedBalances
                    .requireDefault(reservation.getBranchId(), reservation.getProductId())
                    .getId();
            return List.of(new Allocation(balanceId, reservation.getQuantity()));
        }
        List<Allocation> result = new ArrayList<>();
        for (JsonNode node : root) {
            result.add(new Allocation(
                    UUID.fromString(node.get("balanceId").asText()),
                    new BigDecimal(node.get("reservedQuantity").asText())));
        }
        return result;
    }

    private static boolean isTraceable(Product product) {
        return product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock())
                && (Boolean.TRUE.equals(product.getTrackingLot())
                        || Boolean.TRUE.equals(product.getTrackingSerial()));
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Allocation(UUID balanceId, BigDecimal quantity) {}

    private record PickingSelection(
            PickingItem item, List<InventoryTraceabilitySelection> selections) {}

    record PreparedOrderConsumption(
            UUID tenantId,
            UUID branchId,
            List<InventoryReservation> reservations,
            Map<UUID, Product> products) {}
}
