package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.DispatchPackageResponse;
import com.omniretail.backend.logistics.dto.DispatchQueueResponse;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.logistics.entity.Dispatch;
import com.omniretail.backend.logistics.entity.DispatchOperation;
import com.omniretail.backend.logistics.entity.DispatchPackage;
import com.omniretail.backend.logistics.entity.DispatchSourceType;
import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.repository.DispatchOperationRepository;
import com.omniretail.backend.logistics.repository.DispatchPackageRepository;
import com.omniretail.backend.logistics.repository.DispatchRepository;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Confirmacion transaccional del despacho de pedidos ecommerce a domicilio. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DispatchService {

    private final DispatchRepository dispatches;
    private final DispatchPackageRepository packages;
    private final DispatchOperationRepository operations;
    private final PackingRepository packings;
    private final OrderRepository orders;
    private final InventoryReservationRepository reservations;
    private final InventoryReservationLifecycleService reservationLifecycle;
    private final InventoryMovementRepository movements;
    private final ProductRepository products;
    private final InventoryBalanceRepository balances;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    public List<DispatchQueueResponse> getQueue(UUID branchId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        return orders.findByTenantIdAndStatusIn(
                        actor.tenantId(), List.of(OrderStatus.ready_for_dispatch))
                .stream()
                .filter(order -> branchId.equals(order.getBranchId())
                        && order.getDeliveryMethod() == DeliveryMethod.home_delivery)
                .map(order -> packings
                        .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                                actor.tenantId(),
                                branchId,
                                PackingSourceType.order,
                                order.getId())
                        .filter(packing -> packing.getStatus() == PackingStatus.finalized)
                        .map(packing -> new DispatchQueueResponse(
                                order.getId(),
                                order.getOrderNumber(),
                                order.getCreatedAt(),
                                order.getTransportMode(),
                                packing.getId(),
                                packing.getFinalizedAt()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public DispatchResponse getDetail(UUID branchId, UUID orderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Dispatch dispatch = dispatches.findByTenantIdAndOrderId(actor.tenantId(), orderId)
                .orElseThrow(() -> notFound(
                        "DISPATCH_NOT_FOUND", "Despacho no encontrado."));
        if (!branchId.equals(dispatch.getBranchId())) {
            throw notFound("DISPATCH_NOT_FOUND", "Despacho no encontrado.");
        }
        return response(dispatch, false);
    }

    @Transactional
    public DispatchResponse confirm(
            UUID branchId, UUID orderId, ConfirmDispatchRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Order order = orders.findByTenantIdAndIdForUpdate(actor.tenantId(), orderId)
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        if (!branchId.equals(order.getBranchId())) {
            throw notFound("ORDER_NOT_FOUND", "Pedido no encontrado.");
        }
        Packing packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, PackingSourceType.order, orderId)
                .orElseThrow(() -> conflict("PACKING_NOT_FOUND", "El pedido no tiene Packing."));
        List<ConfirmDispatchRequest.PackageRequest> requestedPackages =
                resolvedPackages(packing, request.packages());
        String operationId = request.operationId().trim();
        String fingerprint = fingerprint(
                order, packing, actor.userId(), request, requestedPackages);
        Optional<DispatchOperation> previous =
                operations.findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (previous.isPresent()) {
            return replay(previous.get(), fingerprint);
        }
        if (order.getDeliveryMethod() != DeliveryMethod.home_delivery) {
            throw conflict(
                    "UNSUPPORTED_FULFILLMENT", "El despacho solo admite entrega a domicilio.");
        }
        if (order.getStatus() != OrderStatus.ready_for_dispatch) {
            throw conflict(
                    "INVALID_ORDER_STATUS_TRANSITION", "El pedido no esta listo para despacho.");
        }
        if (packing.getStatus() != PackingStatus.finalized || packing.getFinalizedAt() == null) {
            throw conflict("PACKING_NOT_FINALIZED", "El Packing debe estar finalizado.");
        }
        validateShipment(order, request);
        validatePackages(packing, requestedPackages);
        if (dispatches
                .findBySourceForUpdate(
                        actor.tenantId(), branchId, DispatchSourceType.order, orderId)
                .isPresent()) {
            throw conflict("DISPATCH_ALREADY_EXISTS", "El pedido ya tiene un despacho.");
        }
        List<InventoryReservation> active =
                reservations.findByTenantIdAndSourceTypeAndSourceIdAndStatus(
                        actor.tenantId(),
                        InventoryReservationSourceType.order,
                        orderId,
                        InventoryReservationStatus.active);
        if (active.isEmpty()) {
            throw conflict(
                    "INVENTORY_RESERVATION_NOT_ACTIVE",
                    "El pedido no tiene reservas activas.");
        }
        for (InventoryReservation reservation : active) {
            Product product = products
                    .findByTenantIdAndId(actor.tenantId(), reservation.getProductId())
                    .orElseThrow(() -> notFound(
                            "PRODUCT_NOT_FOUND", "Producto no encontrado."));
            requireTraceabilitySupported(product);
        }

        Instant now = Instant.now();
        Dispatch newDispatch = Dispatch.builder()
                .branchId(branchId)
                .sourceType(DispatchSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .packingId(packing.getId())
                .transportMode(order.getTransportMode())
                .carrierName(trimToNull(request.carrierName()))
                .trackingNumber(trimToNull(request.trackingNumber()))
                .dispatchedByUserId(actor.userId())
                .dispatchedAt(now)
                .build();
        newDispatch.setTenantId(actor.tenantId());
        Dispatch dispatch = dispatches.saveAndFlush(newDispatch);
        for (InventoryReservation reservation : active) {
            reservationLifecycle.consume(actor.tenantId(), reservation.getId());
            for (Allocation allocation : allocations(reservation)) {
                var balance = balances.findByTenantIdAndId(
                                actor.tenantId(), allocation.balanceId())
                        .orElseThrow(() -> conflict(
                                "INVENTORY_RESERVATION_INCONSISTENT",
                                "La reserva no coincide con el balance de inventario."));
                movements.save(InventoryMovement.builder()
                        .tenantId(actor.tenantId())
                        .branchId(branchId)
                        .productId(reservation.getProductId())
                        .type(InventoryMovementType.out)
                        .reason("Despacho ecommerce confirmado")
                        .quantity(allocation.quantity())
                        .quantityBefore(balance.getQuantity().add(allocation.quantity()))
                        .quantityAfter(balance.getQuantity())
                        .fromLocationId(balance.getLocationId())
                        .toLocationId(null)
                        .referenceType("dispatch")
                        .referenceId(dispatch.getId())
                        .performedByUserId(actor.userId())
                        .build());
            }
        }
        List<DispatchPackage> saved = packages.saveAll(requestedPackages.stream()
                .map(packageRequest -> DispatchPackage.builder()
                        .dispatchId(dispatch.getId())
                        .number(packageRequest.number().trim())
                        .weight(packageRequest.weight())
                        .description(trimToNull(packageRequest.description()))
                        .build())
                .toList());
        order.setStatus(OrderStatus.dispatched);
        orders.save(order);
        DispatchResponse result = response(dispatch, saved, false);
        operations.saveAndFlush(DispatchOperation.builder()
                .tenantId(actor.tenantId())
                .branchId(branchId)
                .dispatchId(dispatch.getId())
                .operationId(operationId)
                .fingerprint(fingerprint)
                .resultDispatch(jsonMapper.writeValueAsString(result))
                .build());
        return result;
    }

    private DispatchResponse replay(DispatchOperation operation, String fingerprint) {
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw conflict(
                    "DISPATCH_OPERATION_ID_REUSED",
                    "El operationId ya fue utilizado con una confirmacion diferente.");
        }
        DispatchResponse response =
                jsonMapper.readValue(operation.getResultDispatch(), DispatchResponse.class);
        return new DispatchResponse(
                response.orderId(),
                response.orderStatus(),
                response.dispatchId(),
                response.dispatchStatus(),
                response.transportMode(),
                response.carrierName(),
                response.trackingNumber(),
                response.dispatchedAt(),
                response.packages(),
                true);
    }

    private DispatchResponse response(Dispatch dispatch, boolean idempotent) {
        return response(
                dispatch,
                packages.findByDispatchIdOrderByNumberAsc(dispatch.getId()),
                idempotent);
    }

    private DispatchResponse response(
            Dispatch dispatch, List<DispatchPackage> dispatchPackages, boolean idempotent) {
        return new DispatchResponse(
                dispatch.getOrderId(),
                OrderStatus.dispatched,
                dispatch.getId(),
                dispatch.getStatus(),
                dispatch.getTransportMode(),
                dispatch.getCarrierName(),
                dispatch.getTrackingNumber(),
                dispatch.getDispatchedAt(),
                dispatchPackages.stream()
                        .map(dispatchPackage -> new DispatchPackageResponse(
                                dispatchPackage.getId(),
                                dispatchPackage.getNumber(),
                                dispatchPackage.getWeight(),
                                dispatchPackage.getDescription()))
                        .toList(),
                idempotent);
    }

    private void validateShipment(Order order, ConfirmDispatchRequest request) {
        if (order.getTransportMode() == TransportMode.third_party
                && (trimToNull(request.carrierName()) == null
                        || trimToNull(request.trackingNumber()) == null)) {
            throw BusinessException.badRequest(
                    "Transportista y numero de guia son obligatorios para terceros.");
        }
    }

    private List<ConfirmDispatchRequest.PackageRequest> resolvedPackages(
            Packing packing, List<ConfirmDispatchRequest.PackageRequest> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        if (packing.getPackageCount() == null || packing.getLabelCode() == null) {
            throw conflict(
                    "PACKING_PACKAGE_DATA_MISSING",
                    "Packing no contiene paquetes etiquetados.");
        }
        List<ConfirmDispatchRequest.PackageRequest> generated = new ArrayList<>();
        for (int index = 1; index <= packing.getPackageCount(); index++) {
            generated.add(new ConfirmDispatchRequest.PackageRequest(
                    packing.getLabelCode() + "-" + index,
                    null,
                    "Bulto " + index + " de " + packing.getPackageCount()));
        }
        return generated;
    }

    private void validatePackages(
            Packing packing, List<ConfirmDispatchRequest.PackageRequest> packageRequests) {
        if (packing.getPackageCount() == null
                || packing.getPackageCount() != packageRequests.size()) {
            throw BusinessException.badRequest(
                    "Los paquetes deben coincidir con el packageCount del Packing.");
        }
        Set<String> seen = new HashSet<>();
        for (ConfirmDispatchRequest.PackageRequest packageRequest : packageRequests) {
            if (!seen.add(packageRequest.number().trim().toLowerCase(Locale.ROOT))) {
                throw BusinessException.badRequest(
                        "El numero de paquete no puede repetirse.");
            }
        }
    }

    private void requireTraceabilitySupported(Product product) {
        if (Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial())
                || Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw conflict(
                    "TRACEABILITY_NOT_SUPPORTED",
                    "El producto requiere trazabilidad aun no soportada.");
        }
    }

    private AuthenticatedUser actorForBranch(UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        if (branchId == null || !branchAccessResolver.resolve(actor).allows(branchId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "BRANCH_ACCESS_DENIED",
                    "No tienes acceso a la sucursal indicada.");
        }
        return actor;
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private List<Allocation> allocations(InventoryReservation reservation) {
        JsonNode root = jsonMapper.readTree(reservation.getAllocations());
        if (!root.isArray() || root.isEmpty()) {
            UUID balanceId = balances
                    .findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                            reservation.getTenantId(),
                            reservation.getBranchId(),
                            reservation.getProductId())
                    .orElseThrow(() -> conflict(
                            "INVENTORY_RESERVATION_INCONSISTENT",
                            "La reserva no coincide con el balance de inventario."))
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

    private static String fingerprint(
            Order order,
            Packing packing,
            UUID actor,
            ConfirmDispatchRequest request,
            List<ConfirmDispatchRequest.PackageRequest> packageRequests) {
        String value = order.getId()
                + "|" + packing.getId()
                + "|" + actor
                + "|" + order.getTransportMode()
                + "|" + trimToNull(request.carrierName())
                + "|" + trimToNull(request.trackingNumber())
                + "|" + packageRequests.stream()
                        .map(packageRequest -> packageRequest.number().trim()
                                + ":" + packageRequest.weight()
                                + ":" + trimToNull(packageRequest.description()))
                        .sorted()
                        .reduce("", (left, right) -> left + "|" + right);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Allocation(UUID balanceId, BigDecimal quantity) {}
}
