package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.logistics.dto.DeliveryConfirmationResponse;
import com.omniretail.backend.logistics.entity.Dispatch;
import com.omniretail.backend.logistics.entity.DispatchSourceType;
import com.omniretail.backend.logistics.entity.DispatchStatus;
import com.omniretail.backend.logistics.repository.DispatchRepository;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryConfirmationService {

    private final OrderRepository orders;
    private final DispatchRepository dispatches;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;

    @Transactional
    public DeliveryConfirmationResponse confirm(UUID branchId, UUID orderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Order order = orders.findByTenantIdAndIdForUpdate(actor.tenantId(), orderId)
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        if (!branchId.equals(order.getBranchId())) {
            throw notFound("ORDER_NOT_FOUND", "Pedido no encontrado.");
        }
        if (order.getDeliveryMethod() != DeliveryMethod.home_delivery) {
            throw conflict(
                    "UNSUPPORTED_FULFILLMENT",
                    "La confirmacion final solo admite entrega a domicilio.");
        }
        if (order.getStatus() == OrderStatus.delivered) {
            return deliveredRetry(actor.tenantId(), branchId, order);
        }
        if (order.getStatus() != OrderStatus.dispatched) {
            throw invalidTransition("El pedido no ha sido despachado.");
        }

        Dispatch dispatch = requireDispatch(actor.tenantId(), branchId, orderId);
        if (dispatch.getStatus() != DispatchStatus.dispatched
                || dispatch.getDeliveredAt() != null) {
            throw invalidTransition("El estado del despacho es inconsistente con el pedido.");
        }

        Instant deliveredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        order.setStatus(OrderStatus.delivered);
        order.setDeliveredAt(deliveredAt);
        dispatch.setStatus(DispatchStatus.delivered);
        dispatch.setDeliveredAt(deliveredAt);
        orders.save(order);
        dispatches.saveAndFlush(dispatch);
        return response(order, false);
    }

    private DeliveryConfirmationResponse deliveredRetry(
            UUID tenantId, UUID branchId, Order order) {
        Dispatch dispatch = requireDispatch(tenantId, branchId, order.getId());
        if (order.getDeliveredAt() == null
                || dispatch.getStatus() != DispatchStatus.delivered
                || dispatch.getDeliveredAt() == null
                || !order.getDeliveredAt().equals(dispatch.getDeliveredAt())) {
            throw invalidTransition("La entrega confirmada contiene un historial inconsistente.");
        }
        return response(order, true);
    }

    private Dispatch requireDispatch(UUID tenantId, UUID branchId, UUID orderId) {
        return dispatches
                .findBySourceForUpdate(
                        tenantId, branchId, DispatchSourceType.order, orderId)
                .orElseThrow(() -> notFound(
                        "DISPATCH_NOT_FOUND", "Despacho no encontrado."));
    }

    private AuthenticatedUser actorForBranch(UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        if (branchId == null || !branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        return actor;
    }

    private static DeliveryConfirmationResponse response(Order order, boolean idempotent) {
        return new DeliveryConfirmationResponse(
                order.getId(), order.getStatus(), order.getDeliveredAt(), idempotent);
    }

    private static BusinessException invalidTransition(String message) {
        return conflict("INVALID_ORDER_STATUS_TRANSITION", message);
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
