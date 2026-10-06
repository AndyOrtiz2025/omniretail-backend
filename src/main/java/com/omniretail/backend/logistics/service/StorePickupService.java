package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.logistics.dto.StorePickupHandoverResponse;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entrega fisica al cliente de una compra POS preparada para retiro en tienda. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorePickupService {

    private final OrderRepository orders;
    private final PickingOrderRepository pickings;
    private final PackingRepository packings;
    private final OrderFulfillmentConsumptionService fulfillmentConsumption;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;

    @Transactional
    public StorePickupHandoverResponse handover(UUID branchId, UUID orderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Order order = orders.findByTenantIdAndIdForUpdate(actor.tenantId(), orderId)
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        if (!branchId.equals(order.getBranchId())) {
            throw notFound("ORDER_NOT_FOUND", "Pedido no encontrado.");
        }
        requireStorePickupOrder(order);
        if (order.getStatus() == OrderStatus.delivered) {
            if (order.getDeliveredAt() == null) {
                throw conflict(
                        "STORE_PICKUP_HANDOVER_INCONSISTENT",
                        "El pedido entregado no contiene fecha de entrega.");
            }
            return response(order, true);
        }
        if (order.getStatus() != OrderStatus.ready_for_pickup) {
            throw conflict(
                    "INVALID_ORDER_STATUS_TRANSITION",
                    "El pedido no esta listo para ser entregado al cliente.");
        }

        PickingOrder picking = pickings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, PickingSourceType.order, orderId)
                .filter(found -> found.getStatus() == PickingStatus.completed)
                .orElseThrow(() -> conflict(
                        "PICKING_NOT_COMPLETED",
                        "El Picking debe estar completado antes de la entrega."));
        requireFinalizedPacking(actor.tenantId(), branchId, orderId, picking.getId());

        fulfillmentConsumption.consumeOrder(
                actor.tenantId(),
                branchId,
                orderId,
                actor.userId(),
                "Retiro en tienda entregado al cliente",
                "order",
                orderId);
        order.setStatus(OrderStatus.delivered);
        order.setDeliveredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return response(orders.saveAndFlush(order), false);
    }

    private AuthenticatedUser actorForBranch(UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        if (branchId == null || !branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        return actor;
    }

    private static void requireStorePickupOrder(Order order) {
        if (order.getSource() != OrderSource.pos
                || order.getDeliveryMethod() != DeliveryMethod.store_pickup) {
            throw conflict(
                    "STORE_PICKUP_ORDER_NOT_ELIGIBLE",
                    "El pedido no corresponde a un retiro en tienda POS.");
        }
    }

    private void requireFinalizedPacking(
            UUID tenantId, UUID branchId, UUID orderId, UUID pickingOrderId) {
        packings.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        tenantId, branchId, PackingSourceType.order, orderId)
                .filter(found -> found.getPickingOrderId().equals(pickingOrderId))
                .filter(found -> found.getStatus() == PackingStatus.finalized
                        && found.getFinalizedAt() != null)
                .orElseThrow(() -> conflict(
                        "PACKING_NOT_FINALIZED",
                        "El Packing debe estar finalizado antes de la entrega."));
    }

    private static StorePickupHandoverResponse response(Order order, boolean idempotent) {
        return new StorePickupHandoverResponse(
                order.getId(), order.getStatus(), order.getDeliveredAt(), idempotent);
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
