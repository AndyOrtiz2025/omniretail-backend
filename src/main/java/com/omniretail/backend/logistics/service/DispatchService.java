package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.service.OrderEmailNotifier;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.dto.InventoryPhysicalSelection;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.service.InventoryOperationalLocationService;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.inventory.service.InventoryPhysicalSelectionCodec;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.ConfirmTransferDispatchRequest;
import com.omniretail.backend.logistics.dto.DispatchPackageResponse;
import com.omniretail.backend.logistics.dto.DispatchQueueResponse;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.logistics.dto.PreparedDispatchResponse;
import com.omniretail.backend.logistics.entity.Dispatch;
import com.omniretail.backend.logistics.entity.DispatchOperation;
import com.omniretail.backend.logistics.entity.DispatchPackage;
import com.omniretail.backend.logistics.entity.DispatchSourceType;
import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.DispatchOperationRepository;
import com.omniretail.backend.logistics.repository.DispatchPackageRepository;
import com.omniretail.backend.logistics.repository.DispatchRepository;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Confirmacion transaccional de despachos de pedidos y transferencias. */
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
    private final InventoryTraceabilityMutationService traceabilityMutation;
    private final InventoryOperationalLocationService operationalLocationService;
    private final InventoryPhysicalSelectionCodec physicalSelectionCodec;
    private final InventoryMovementRepository movements;
    private final ProductRepository products;
    private final InventoryBalanceRepository balances;
    private final InventoryTransferRepository transfers;
    private final InventoryTransferItemRepository transferItems;
    private final PickingOrderRepository pickingOrders;
    private final PickingItemRepository pickingItems;
    private final OrderFulfillmentConsumptionService orderFulfillmentConsumption;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final JsonMapper jsonMapper;
    private final OrderEmailNotifier orderEmailNotifier;

    public List<DispatchQueueResponse> getQueue(UUID branchId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        List<DispatchQueueResponse> result = new ArrayList<>(orders.findByTenantIdAndStatusIn(
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
                                packing.getFinalizedAt(),
                                DispatchSourceType.order,
                                order.getId(),
                                order.getOrderNumber()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList());
        List<InventoryTransfer> transferQueue =
                transfers.findByTenantIdAndSourceBranchIdAndStatusOrderByCreatedAtAsc(
                        actor.tenantId(), branchId, InventoryTransferStatus.preparing);
        if (!transferQueue.isEmpty()) {
            tenantCapabilityGuard.ensureTenantCapability(
                    actor.tenantId(), SaasCapability.inventory);
        }
        transferQueue.forEach(transfer -> packings
                        .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                                actor.tenantId(),
                                branchId,
                                PackingSourceType.transfer,
                                transfer.getId())
                        .filter(packing -> packing.getStatus() == PackingStatus.finalized)
                        .ifPresent(packing -> result.add(new DispatchQueueResponse(
                                null,
                                null,
                                transfer.getCreatedAt(),
                                TransportMode.own_fleet,
                                packing.getId(),
                                packing.getFinalizedAt(),
                                DispatchSourceType.transfer,
                                transfer.getId(),
                                transfer.getNumber()))));
        return result;
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

    public PreparedDispatchResponse getPreparedDetail(UUID branchId, UUID orderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Order order = orders.findByTenantIdAndId(actor.tenantId(), orderId)
                .filter(found -> branchId.equals(found.getBranchId()))
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        if (order.getDeliveryMethod() != DeliveryMethod.home_delivery) {
            throw conflict(
                    "UNSUPPORTED_FULFILLMENT",
                    "El despacho solo admite entrega a domicilio.");
        }
        if (order.getStatus() != OrderStatus.ready_for_dispatch) {
            throw conflict(
                    "INVALID_ORDER_STATUS_TRANSITION",
                    "El pedido no esta listo para despacho.");
        }
        PickingOrder picking = pickingOrders
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, PickingSourceType.order, orderId)
                .orElseThrow(() -> conflict(
                        "PICKING_NOT_FOUND", "El pedido no tiene Picking."));
        if (picking.getStatus() != PickingStatus.completed || picking.getCompletedAt() == null) {
            throw conflict("PICKING_NOT_COMPLETED", "El Picking debe estar completado.");
        }
        Packing packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, PackingSourceType.order, orderId)
                .orElseThrow(() -> conflict("PACKING_NOT_FOUND", "El pedido no tiene Packing."));
        if (!picking.getId().equals(packing.getPickingOrderId())
                || packing.getStatus() != PackingStatus.finalized
                || packing.getFinalizedAt() == null) {
            throw conflict("PACKING_NOT_FINALIZED", "El Packing debe estar finalizado.");
        }

        JsonNode deliveryAddress = json(order.getDeliveryAddress());
        JsonNode guestCustomer = json(order.getGuestCustomer());
        return new PreparedDispatchResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getCreatedAt(),
                order.getStatus(),
                text(deliveryAddress, guestCustomer, "recipientName", "name"),
                text(deliveryAddress, guestCustomer, "recipientPhone", "phone"),
                deliveryAddress,
                json(order.getNotificationContact()),
                order.getTransportMode(),
                picking.getId(),
                picking.getStatus(),
                picking.getCompletedAt(),
                packing.getId(),
                packing.getStatus(),
                packing.getFinalizedAt(),
                packing.getPackageCount(),
                packing.getTotalWeight(),
                packing.getLabelCode());
    }

    public DispatchResponse getTransferDetail(UUID branchId, UUID transferId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        tenantCapabilityGuard.ensureTenantCapability(
                actor.tenantId(), SaasCapability.inventory);
        InventoryTransfer transfer = transfers
                .findByTenantIdAndId(actor.tenantId(), transferId)
                .filter(found -> branchId.equals(found.getSourceBranchId()))
                .orElseThrow(() -> notFound(
                        "INVENTORY_TRANSFER_NOT_FOUND", "Transferencia no encontrada."));
        Dispatch dispatch = dispatches
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, DispatchSourceType.transfer, transferId)
                .orElseThrow(() -> notFound(
                        "DISPATCH_NOT_FOUND", "Despacho no encontrado."));
        return transferResponse(dispatch, transfer, false);
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
        OrderFulfillmentConsumptionService.PreparedOrderConsumption preparedConsumption =
                orderFulfillmentConsumption.prepareOrder(actor.tenantId(), branchId, orderId);
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
        orderFulfillmentConsumption.consumePrepared(
                preparedConsumption,
                actor.userId(),
                "Despacho ecommerce confirmado",
                "dispatch",
                dispatch.getId());
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
        orderEmailNotifier.orderDispatched(order);
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

    @Transactional
    public DispatchResponse confirmTransfer(
            UUID branchId, UUID transferId, ConfirmTransferDispatchRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        tenantCapabilityGuard.ensureTenantCapability(
                actor.tenantId(), SaasCapability.inventory);
        InventoryTransfer transfer = transfers
                .findForUpdateByTenantIdAndId(actor.tenantId(), transferId)
                .orElseThrow(() -> notFound(
                        "INVENTORY_TRANSFER_NOT_FOUND", "Transferencia no encontrada."));
        if (!branchId.equals(transfer.getSourceBranchId())) {
            throw notFound("INVENTORY_TRANSFER_NOT_FOUND", "Transferencia no encontrada.");
        }
        Packing packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, PackingSourceType.transfer, transferId)
                .orElseThrow(() -> conflict(
                        "PACKING_NOT_FOUND", "La transferencia no tiene Packing."));
        if (packing.getStatus() != PackingStatus.finalized || packing.getFinalizedAt() == null) {
            throw conflict("PACKING_NOT_FINALIZED", "El Packing debe estar finalizado.");
        }
        List<ConfirmDispatchRequest.PackageRequest> requestedPackages =
                resolvedPackages(packing, null);
        String operationId = request.operationId().trim();
        String fingerprint = transferFingerprint(
                transfer, packing, actor.userId(), requestedPackages);
        Optional<DispatchOperation> previous =
                operations.findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (previous.isPresent()) {
            return replay(previous.get(), fingerprint);
        }
        if (transfer.getStatus() != InventoryTransferStatus.preparing) {
            throw conflict(
                    "INVALID_INVENTORY_TRANSFER_STATE",
                    "La transferencia no se encuentra en preparacion.");
        }
        validatePackages(packing, requestedPackages);
        if (dispatches
                .findBySourceForUpdate(
                        actor.tenantId(), branchId, DispatchSourceType.transfer, transferId)
                .isPresent()) {
            throw conflict(
                    "DISPATCH_ALREADY_EXISTS",
                    "La transferencia ya tiene un despacho.");
        }

        List<InventoryTransferItem> items = transferItems
                .findByTenantIdAndTransferIdOrderByIdAsc(actor.tenantId(), transferId);
        List<InventoryReservation> foundReservations = reservations
                .findByTenantIdAndSourceTypeAndSourceId(
                        actor.tenantId(), InventoryReservationSourceType.transfer, transferId);
        Map<UUID, InventoryReservation> reservationsByLine = foundReservations.stream()
                .collect(Collectors.toMap(
                        InventoryReservation::getSourceLineId, Function.identity()));
        if (items.isEmpty() || items.size() != foundReservations.size()) {
            throw inconsistentTransferReservation();
        }
        List<TransferLine> lines = items.stream()
                .map(item -> new TransferLine(
                        item,
                        requireTransferReservation(
                                transfer, item, reservationsByLine.get(item.getId()))))
                .sorted(Comparator.comparing(line -> line.reservation().getId()))
                .toList();
        Map<UUID, Product> transferProducts = new java.util.HashMap<>();
        for (TransferLine line : lines) {
            Product product = products
                    .findByTenantIdAndId(actor.tenantId(), line.item().getProductId())
                    .orElseThrow(() -> notFound(
                            "PRODUCT_NOT_FOUND", "Producto no encontrado."));
            requireTransferProduct(product);
            transferProducts.put(product.getId(), product);
        }
        // Ultima barrera antes de dejar mercancia en transito: la configuracion del destino pudo cambiar
        // desde la aprobacion. 409 sin tocar reservas, estados ni existencias.
        operationalLocationService.requireTransferDestinationReceivable(
                actor.tenantId(), transfer.getDestinationBranchId(), transferProducts.keySet());

        Instant now = Instant.now();
        Dispatch newDispatch = Dispatch.builder()
                .branchId(branchId)
                .sourceType(DispatchSourceType.transfer)
                .sourceId(transferId)
                .orderId(null)
                .packingId(packing.getId())
                .transportMode(TransportMode.own_fleet)
                .carrierName(null)
                .trackingNumber(null)
                .dispatchedByUserId(actor.userId())
                .dispatchedAt(now)
                .build();
        newDispatch.setTenantId(actor.tenantId());
        Dispatch dispatch = dispatches.saveAndFlush(newDispatch);

        for (TransferLine line : lines) {
            InventoryReservation reservation = line.reservation();
            List<Allocation> reservationAllocations = transferAllocations(reservation);
            Product product = transferProducts.get(line.item().getProductId());
            BigDecimal aggregateBefore = isTraceable(product)
                    ? aggregateQuantity(actor.tenantId(), reservationAllocations)
                    : null;
            reservationLifecycle.consume(actor.tenantId(), reservation.getId());
            if (isTraceable(product)) {
                PickingSelection selection = requirePickingSelection(
                        actor.tenantId(), branchId, PickingSourceType.transfer, transferId,
                        line.item().getId(), product, reservation.getQuantity());
                traceabilityMutation.consumePhysicalReservation(
                        actor.tenantId(),
                        branchId,
                        product,
                        selection.item().getLocationId(),
                        reservation.getQuantity(),
                        selection.selections(),
                        InventorySerialStatus.IN_TRANSIT,
                        aggregateBefore,
                        aggregateBefore.subtract(reservation.getQuantity()),
                        "Salida por traslado " + transfer.getNumber(),
                        "transfer",
                        transfer.getId(),
                        line.item().getId(),
                        actor.userId());
            } else {
                for (Allocation allocation : reservationAllocations) {
                    var balance = balances.findByTenantIdAndId(
                                    actor.tenantId(), allocation.balanceId())
                            .orElseThrow(DispatchService::inconsistentTransferReservation);
                    movements.save(InventoryMovement.builder()
                            .tenantId(actor.tenantId())
                            .branchId(branchId)
                            .productId(line.item().getProductId())
                            .type(InventoryMovementType.out)
                            .reason("Salida por traslado " + transfer.getNumber())
                            .quantity(allocation.quantity())
                            .quantityBefore(balance.getQuantity().add(allocation.quantity()))
                            .quantityAfter(balance.getQuantity())
                            .fromLocationId(balance.getLocationId())
                            .toLocationId(null)
                            .referenceType("transfer")
                            .referenceId(transfer.getId())
                            .referenceLineId(line.item().getId())
                            .performedByUserId(actor.userId())
                            .build());
                }
            }
            line.item().setDispatchedQuantity(reservation.getQuantity());
            transferItems.save(line.item());
        }

        List<DispatchPackage> saved = packages.saveAll(requestedPackages.stream()
                .map(packageRequest -> DispatchPackage.builder()
                        .dispatchId(dispatch.getId())
                        .number(packageRequest.number().trim())
                        .weight(packageRequest.weight())
                        .description(trimToNull(packageRequest.description()))
                        .build())
                .toList());
        transfer.setStatus(InventoryTransferStatus.inTransit);
        transfer.setDispatchedByUserId(actor.userId());
        transfer.setDispatchedAt(now);
        transfers.saveAndFlush(transfer);

        DispatchResponse result = transferResponse(dispatch, saved, transfer, false);
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
                true,
                response.sourceType(),
                response.sourceId(),
                response.sourceReference(),
                response.transferStatus());
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
                idempotent,
                DispatchSourceType.order,
                dispatch.getSourceId(),
                orders.findByTenantIdAndId(dispatch.getTenantId(), dispatch.getSourceId())
                        .map(Order::getOrderNumber)
                        .orElse(null),
                null);
    }

    private DispatchResponse transferResponse(
            Dispatch dispatch, InventoryTransfer transfer, boolean idempotent) {
        return transferResponse(
                dispatch,
                packages.findByDispatchIdOrderByNumberAsc(dispatch.getId()),
                transfer,
                idempotent);
    }

    private DispatchResponse transferResponse(
            Dispatch dispatch,
            List<DispatchPackage> dispatchPackages,
            InventoryTransfer transfer,
            boolean idempotent) {
        return new DispatchResponse(
                null,
                null,
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
                idempotent,
                DispatchSourceType.transfer,
                transfer.getId(),
                transfer.getNumber(),
                transfer.getStatus());
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

    private static void requireTransferProduct(Product product) {
        if (product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())) {
            throw conflict(
                    "INVENTORY_TRANSFER_PRODUCT_UNSUPPORTED",
                    "Solo productos fisicos con control de inventario pueden despacharse.");
        }
    }

    private static void requireTraceabilitySupportedStatic(Product product) {
        if (Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial())
                || Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw conflict(
                    "TRACEABILITY_NOT_SUPPORTED",
                    "El producto requiere trazabilidad aun no soportada.");
        }
    }

    private PickingSelection requirePickingSelection(
            UUID tenantId,
            UUID branchId,
            PickingSourceType sourceType,
            UUID sourceId,
            UUID sourceLineId,
            Product product,
            BigDecimal quantity) {
        PickingOrder picking = pickingOrders
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        tenantId, branchId, sourceType, sourceId)
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

    private BigDecimal aggregateQuantity(UUID tenantId, List<Allocation> allocations) {
        return allocations.stream()
                .map(allocation -> balances.findByTenantIdAndId(tenantId, allocation.balanceId())
                        .orElseThrow(DispatchService::inconsistentTransferReservation)
                        .getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean isTraceable(Product product) {
        return product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock())
                && (Boolean.TRUE.equals(product.getTrackingLot())
                        || Boolean.TRUE.equals(product.getTrackingSerial()));
    }

    private static InventoryReservation requireTransferReservation(
            InventoryTransfer transfer,
            InventoryTransferItem item,
            InventoryReservation reservation) {
        if (reservation == null
                || reservation.getStatus() != InventoryReservationStatus.active
                || reservation.getSourceType() != InventoryReservationSourceType.transfer
                || !transfer.getId().equals(reservation.getSourceId())
                || !item.getId().equals(reservation.getSourceLineId())
                || !item.getProductId().equals(reservation.getProductId())
                || !transfer.getSourceBranchId().equals(reservation.getBranchId())
                || reservation.getQuantity().compareTo(item.getRequestedQuantity()) != 0
                || item.getDispatchedQuantity().signum() != 0
                || item.getReceivedQuantity().signum() != 0
                || reservation.getOrderId() != null
                || reservation.getOrderItemId() != null) {
            throw inconsistentTransferReservation();
        }
        return reservation;
    }

    private static BusinessException inconsistentTransferReservation() {
        return conflict(
                "INVENTORY_RESERVATION_INCONSISTENT",
                "Las reservas de la transferencia no son consistentes.");
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

    private JsonNode json(String value) {
        return value == null ? null : jsonMapper.readTree(value);
    }

    private static String text(
            JsonNode primary, JsonNode fallback, String primaryField, String fallbackField) {
        JsonNode value = primary == null ? null : primary.get(primaryField);
        if (value == null || value.isNull()) {
            value = fallback == null ? null : fallback.get(fallbackField);
        }
        return value == null || value.isNull() ? null : value.asText();
    }

    private List<Allocation> transferAllocations(InventoryReservation reservation) {
        JsonNode root = jsonMapper.readTree(reservation.getAllocations());
        if (!root.isArray() || root.isEmpty()) {
            throw inconsistentTransferReservation();
        }
        List<Allocation> result = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode node : root) {
            if (node.get("balanceId") == null
                    || node.get("reservedQuantity") == null
                    || (node.get("consumedQuantity") != null
                            && new BigDecimal(node.get("consumedQuantity").asText()).signum() != 0)) {
                throw inconsistentTransferReservation();
            }
            BigDecimal quantity = new BigDecimal(node.get("reservedQuantity").asText());
            if (quantity.signum() <= 0) {
                throw inconsistentTransferReservation();
            }
            result.add(new Allocation(
                    UUID.fromString(node.get("balanceId").asText()), quantity));
            total = total.add(quantity);
        }
        if (total.compareTo(reservation.getQuantity()) != 0) {
            throw inconsistentTransferReservation();
        }
        return result.stream()
                .sorted(Comparator.comparing(Allocation::balanceId))
                .toList();
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

    private static String transferFingerprint(
            InventoryTransfer transfer,
            Packing packing,
            UUID actor,
            List<ConfirmDispatchRequest.PackageRequest> packageRequests) {
        String value = transfer.getId()
                + "|" + packing.getId()
                + "|" + actor
                + "|" + TransportMode.own_fleet
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

    private record TransferLine(
            InventoryTransferItem item, InventoryReservation reservation) {}

    private record PickingSelection(
            PickingItem item, List<InventoryTraceabilitySelection> selections) {}
}
