package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.logistics.dto.CreatePickingIncidentRequest;
import com.omniretail.backend.logistics.dto.PickingActionResponse;
import com.omniretail.backend.logistics.dto.PickingDetailResponse;
import com.omniretail.backend.logistics.dto.PickingIncidentResponse;
import com.omniretail.backend.logistics.dto.PickingLineResponse;
import com.omniretail.backend.logistics.dto.PickingProgressResponse;
import com.omniretail.backend.logistics.dto.PickingQueueResponse;
import com.omniretail.backend.logistics.dto.PickingReleaseResponse;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.entity.PickingAssignmentRelease;
import com.omniretail.backend.logistics.entity.PickingIncident;
import com.omniretail.backend.logistics.entity.PickingIncidentStatus;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingItemStatus;
import com.omniretail.backend.logistics.entity.PickingItemUpdateOperation;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingPriority;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PickingAssignmentReleaseRepository;
import com.omniretail.backend.logistics.repository.PickingIncidentRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingItemUpdateOperationRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PickingService {

    private static final List<PickingStatus> QUEUE_STATUSES =
            List.of(PickingStatus.pending, PickingStatus.assigned, PickingStatus.in_progress);
    private static final TypeReference<List<ReservationAllocation>> ALLOCATIONS_TYPE =
            new TypeReference<>() {};

    private final PickingOrderRepository pickingOrderRepository;
    private final PickingItemRepository pickingItemRepository;
    private final PickingIncidentRepository pickingIncidentRepository;
    private final PickingAssignmentReleaseRepository releaseRepository;
    private final PickingItemUpdateOperationRepository operationRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final LocationRepository locationRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    /** Se invoca dentro de la misma transaccion que confirma el checkout. */
    @Transactional
    public Optional<PickingOrder> ensureForOrder(UUID tenantId, UUID orderId) {
        Order order = orderRepository.findByTenantIdAndIdForUpdate(tenantId, orderId)
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        requireEligibleOrderSource(order);

        Optional<PickingOrder> existing = pickingOrderRepository
                .findByTenantIdAndSourceTypeAndSourceId(tenantId, PickingSourceType.order, orderId);
        if (existing.isPresent()) return existing;
        if (order.getStatus() != OrderStatus.confirmed) {
            throw conflict("PICKING_ORDER_NOT_ELIGIBLE", "El pedido no está confirmado.");
        }

        List<OrderItem> orderItems = orderItemRepository.findByOrderId(orderId);
        Map<UUID, Product> products = productRepository
                .findByTenantIdAndIdIn(tenantId, orderItems.stream().map(OrderItem::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<OrderItem> physicalItems = orderItems.stream()
                .filter(item -> {
                    Product product = products.get(item.getProductId());
                    return product != null && product.getProductType() == ProductType.physical;
                })
                .toList();
        if (physicalItems.isEmpty()) return Optional.empty();

        Map<ReservationKey, InventoryReservation> reservations = reservationRepository
                .findByTenantIdAndSourceTypeAndSourceIdAndStatus(
                        tenantId,
                        InventoryReservationSourceType.order,
                        orderId,
                        InventoryReservationStatus.active)
                .stream()
                .collect(Collectors.toMap(
                        reservation -> new ReservationKey(
                                reservation.getSourceLineId(), reservation.getProductId()),
                        Function.identity()));

        PickingOrder picking = PickingOrder.builder()
                .branchId(order.getBranchId())
                .sourceType(PickingSourceType.order)
                .sourceId(order.getId())
                .orderId(order.getId())
                .priority(PickingPriority.normal)
                .build();
        picking.setTenantId(tenantId);
        picking = pickingOrderRepository.save(picking);

        for (OrderItem orderItem : physicalItems) {
            Product product = products.get(orderItem.getProductId());
            InventoryReservation reservation = reservations.get(
                    new ReservationKey(orderItem.getId(), product.getId()));
            if (Boolean.TRUE.equals(product.getTrackingStock()) && reservation == null) {
                throw conflict(
                        "PICKING_RESERVATION_REQUIRED",
                        "La linea de Picking no posee una reserva activa.");
            }
            BigDecimal requestedQuantity = reservation != null
                    ? reservation.getQuantity()
                    : Optional.ofNullable(orderItem.getInventoryQuantity()).orElse(orderItem.getQuantity());
            PickingItem item = PickingItem.builder()
                    .pickingOrderId(picking.getId())
                    .sourceLineId(orderItem.getId())
                    .orderItemId(orderItem.getId())
                    .productId(product.getId())
                    .requestedQuantity(requestedQuantity)
                    .locationId(firstLocation(reservation))
                    .build();
            item.setTenantId(tenantId);
            pickingItemRepository.save(item);
        }
        return Optional.of(picking);
    }

    public List<PickingQueueResponse> getQueue(UUID branchId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        return pickingOrderRepository
                .findByTenantIdAndBranchIdAndSourceTypeAndStatusInOrderByCreatedAtAsc(
                        actor.tenantId(), branchId, PickingSourceType.order, QUEUE_STATUSES)
                .stream()
                .map(picking -> queueResponse(actor.tenantId(), picking))
                .toList();
    }

    public PickingDetailResponse getDetail(UUID branchId, UUID pickingOrderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = findScoped(actor.tenantId(), branchId, pickingOrderId);
        requireOrderSource(picking);
        return detail(actor.tenantId(), picking);
    }

    @Transactional
    public PickingActionResponse assign(UUID branchId, UUID pickingOrderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = lockScoped(actor.tenantId(), branchId, pickingOrderId);
        Order order = lockOrder(picking);
        requireMutableOrderPicking(picking, order);
        requireTraceabilitySupported(actor.tenantId(), picking.getId());

        if (actor.userId().equals(picking.getAssignedUserId())) {
            return action(picking, order, true);
        }
        if (picking.getAssignedUserId() != null) {
            throw conflict("PICKING_ALREADY_ASSIGNED", "El Picking ya está asignado a otro usuario.");
        }
        boolean hasProgress = hasProgress(actor.tenantId(), branchId, picking.getId());
        if (hasProgress && order.getStatus() != OrderStatus.picking) {
            throw conflict("INVALID_PICKING_STATE", "El pedido no se encuentra en Picking.");
        }
        if (!hasProgress && order.getStatus() != OrderStatus.confirmed
                && order.getStatus() != OrderStatus.preparing) {
            throw conflict("INVALID_ORDER_STATUS_TRANSITION", "El pedido no puede iniciar Picking.");
        }
        picking.setAssignedUserId(actor.userId());
        picking.setStatus(hasProgress ? PickingStatus.in_progress : PickingStatus.assigned);
        if (order.getStatus() == OrderStatus.confirmed) order.setStatus(OrderStatus.preparing);
        orderRepository.save(order);
        return action(pickingOrderRepository.saveAndFlush(picking), order, false);
    }

    @Transactional
    public PickingReleaseResponse release(UUID branchId, UUID pickingOrderId, String reason) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = lockScoped(actor.tenantId(), branchId, pickingOrderId);
        requireOrderSource(picking);
        requireNonTerminal(picking);
        lockOrder(picking);
        if (!actor.userId().equals(picking.getAssignedUserId())) {
            throw conflict("PICKING_NOT_ASSIGNED_TO_ACTOR", "Solo el usuario asignado puede liberar el Picking.");
        }
        String normalizedReason = reason.trim();
        boolean hasProgress = hasProgress(actor.tenantId(), branchId, picking.getId());
        picking.setAssignedUserId(null);
        picking.setStatus(hasProgress ? PickingStatus.in_progress : PickingStatus.pending);
        pickingOrderRepository.save(picking);
        PickingAssignmentRelease release = releaseRepository.saveAndFlush(
                PickingAssignmentRelease.builder()
                        .tenantId(actor.tenantId())
                        .branchId(branchId)
                        .pickingOrderId(picking.getId())
                        .actorUserId(actor.userId())
                        .reason(normalizedReason)
                        .build());
        return PickingReleaseResponse.from(release);
    }

    @Transactional
    public PickingLineResponse updateItem(
            UUID branchId,
            UUID pickingOrderId,
            UUID pickingItemId,
            UpdatePickingItemRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = lockScoped(actor.tenantId(), branchId, pickingOrderId);
        requireOrderSource(picking);
        PickingItem item = pickingItemRepository
                .findByScopeAndIdForUpdate(actor.tenantId(), branchId, pickingOrderId, pickingItemId)
                .orElseThrow(() -> notFound("PICKING_ITEM_NOT_FOUND", "Linea de Picking no encontrada."));

        String operationId = request.operationId().trim();
        String fingerprint = fingerprint(item.getId(), request, actor.userId());
        Optional<PickingItemUpdateOperation> existing =
                operationRepository.findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (existing.isPresent()) {
            PickingItemUpdateOperation operation = existing.get();
            if (!operation.getPickingItemId().equals(item.getId())
                    || !operation.getFingerprint().equals(fingerprint)) {
                throw conflict(
                        "PICKING_OPERATION_ID_REUSED",
                        "El operationId ya fue utilizado con una mutacion diferente.");
            }
            return jsonMapper.readValue(operation.getResultItem(), PickingLineResponse.class);
        }
        requireNonTerminal(picking);
        requireAssignedActor(picking, actor);
        Order order = lockOrder(picking);
        Product product = requireProduct(actor.tenantId(), item.getProductId());
        requireTraceabilitySupported(product);

        BigDecimal target = request.pickedQuantity();
        if (target.signum() < 0) {
            throw BusinessException.badRequest("La cantidad tomada no puede ser negativa.");
        }
        if (target.compareTo(item.getRequestedQuantity()) > 0) {
            throw conflict("PICKING_QUANTITY_EXCEEDED", "La cantidad tomada excede la requerida.");
        }
        if (target.compareTo(item.getPickedQuantity()) < 0) {
            throw conflict("PICKING_QUANTITY_DECREASE", "La cantidad tomada no puede disminuir.");
        }
        if (target.compareTo(item.getPickedQuantity()) == 0) {
            throw conflict("PICKING_ITEM_UNCHANGED", "La linea de Picking no contiene cambios.");
        }
        applyLocation(actor.tenantId(), branchId, item, request.locationId());
        item.setPickedQuantity(target);
        item.setStatus(itemStatus(target, item.getRequestedQuantity()));
        pickingItemRepository.saveAndFlush(item);

        if (order.getStatus() == OrderStatus.preparing) {
            order.setStatus(OrderStatus.picking);
        } else if (order.getStatus() != OrderStatus.picking) {
            throw conflict("INVALID_ORDER_STATUS_TRANSITION", "El pedido no se encuentra listo para Picking.");
        }
        if (picking.getStatus() == PickingStatus.assigned) {
            picking.setStatus(PickingStatus.in_progress);
            picking.setStartedAt(Instant.now());
        } else if (picking.getStatus() != PickingStatus.in_progress) {
            throw conflict("INVALID_PICKING_STATE", "El Picking no se encuentra iniciado.");
        }
        orderRepository.save(order);
        pickingOrderRepository.save(picking);

        PickingLineResponse result = lineResponse(
                actor.tenantId(), branchId, picking.getOrderId(), item, product);
        operationRepository.saveAndFlush(PickingItemUpdateOperation.builder()
                .tenantId(actor.tenantId())
                .pickingItemId(item.getId())
                .operationId(operationId)
                .fingerprint(fingerprint)
                .resultItem(jsonMapper.writeValueAsString(result))
                .build());
        return result;
    }

    @Transactional
    public PickingIncidentResponse createIncident(
            UUID branchId, UUID pickingOrderId, CreatePickingIncidentRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = lockScoped(actor.tenantId(), branchId, pickingOrderId);
        requireOrderSource(picking);
        requireNonTerminal(picking);
        requireAssignedActor(picking, actor);
        lockOrder(picking);
        if (request.pickingLineId() != null) {
            PickingItem item = pickingItemRepository
                    .findByScopeAndId(actor.tenantId(), branchId, request.pickingLineId())
                    .filter(found -> found.getPickingOrderId().equals(pickingOrderId))
                    .orElseThrow(() -> notFound("PICKING_ITEM_NOT_FOUND", "Linea de Picking no encontrada."));
            if (request.quantityAffected() != null
                    && request.quantityAffected().compareTo(item.getRequestedQuantity()) > 0) {
                throw BusinessException.badRequest("La cantidad afectada excede la cantidad requerida.");
            }
        }
        PickingIncident incident = PickingIncident.builder()
                .branchId(branchId)
                .pickingOrderId(pickingOrderId)
                .pickingItemId(request.pickingLineId())
                .incidentType(request.type())
                .quantityAffected(request.quantityAffected())
                .comment(request.comment().trim())
                .createdByUserId(actor.userId())
                .build();
        incident.setTenantId(actor.tenantId());
        return PickingIncidentResponse.from(pickingIncidentRepository.saveAndFlush(incident));
    }

    @Transactional
    public PickingIncidentResponse resolveIncident(
            UUID branchId, UUID pickingOrderId, UUID incidentId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = lockScoped(actor.tenantId(), branchId, pickingOrderId);
        requireOrderSource(picking);
        requireNonTerminal(picking);
        requireAssignedActor(picking, actor);
        lockOrder(picking);
        PickingIncident incident = pickingIncidentRepository
                .findByScopeAndIdForUpdate(actor.tenantId(), branchId, pickingOrderId, incidentId)
                .orElseThrow(() -> notFound("PICKING_INCIDENT_NOT_FOUND", "Incidencia no encontrada."));
        if (incident.getStatus() == PickingIncidentStatus.resolved) {
            if (actor.userId().equals(incident.getResolvedByUserId())) {
                return PickingIncidentResponse.from(incident);
            }
            throw conflict("PICKING_INCIDENT_ALREADY_RESOLVED", "La incidencia ya fue resuelta.");
        }
        incident.setStatus(PickingIncidentStatus.resolved);
        incident.setResolvedByUserId(actor.userId());
        incident.setResolvedAt(Instant.now());
        return PickingIncidentResponse.from(pickingIncidentRepository.saveAndFlush(incident));
    }

    @Transactional
    public PickingActionResponse complete(UUID branchId, UUID pickingOrderId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = lockScoped(actor.tenantId(), branchId, pickingOrderId);
        Order order = lockOrder(picking);
        requireOrderSource(picking);
        requireTraceabilitySupported(actor.tenantId(), picking.getId());
        if (picking.getStatus() == PickingStatus.completed) return action(picking, order, true);
        requireNonTerminal(picking);
        requireAssignedActor(picking, actor);
        if (picking.getStatus() != PickingStatus.in_progress) {
            throw conflict("INVALID_PICKING_STATE", "El Picking debe estar en progreso para completarse.");
        }
        List<PickingItem> items = pickingItemRepository.findByScopeAndPickingOrderId(
                actor.tenantId(), branchId, pickingOrderId);
        if (items.isEmpty() || items.stream().anyMatch(item ->
                item.getStatus() != PickingItemStatus.completed
                        || item.getPickedQuantity().compareTo(item.getRequestedQuantity()) != 0)) {
            throw conflict("PICKING_ITEMS_INCOMPLETE", "Todas las lineas deben estar completas.");
        }
        if (pickingIncidentRepository.existsByTenantIdAndBranchIdAndPickingOrderIdAndStatus(
                actor.tenantId(), branchId, pickingOrderId, PickingIncidentStatus.open)) {
            throw conflict("PICKING_HAS_OPEN_INCIDENTS", "El Picking posee incidencias abiertas.");
        }
        if (order.getStatus() != OrderStatus.picking) {
            throw conflict("INVALID_ORDER_STATUS_TRANSITION", "El pedido no se encuentra en Picking.");
        }
        picking.setStatus(PickingStatus.completed);
        picking.setCompletedAt(Instant.now());
        return action(pickingOrderRepository.saveAndFlush(picking), order, false);
    }

    private AuthenticatedUser actorForBranch(UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        return actor;
    }

    private PickingOrder findScoped(UUID tenantId, UUID branchId, UUID id) {
        return pickingOrderRepository.findByTenantIdAndBranchIdAndId(tenantId, branchId, id)
                .orElseThrow(() -> notFound("PICKING_NOT_FOUND", "Picking no encontrado."));
    }

    private PickingOrder lockScoped(UUID tenantId, UUID branchId, UUID id) {
        return pickingOrderRepository.findByScopeAndIdForUpdate(tenantId, branchId, id)
                .orElseThrow(() -> notFound("PICKING_NOT_FOUND", "Picking no encontrado."));
    }

    private Order lockOrder(PickingOrder picking) {
        requireOrderSource(picking);
        Order order = orderRepository.findByTenantIdAndIdForUpdate(
                        picking.getTenantId(), picking.getOrderId())
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        requireMatchingOrder(picking, order);
        requireEligibleOrderSource(order);
        return order;
    }

    private static void requireMatchingOrder(PickingOrder picking, Order order) {
        if (!order.getId().equals(picking.getOrderId())
                || !order.getBranchId().equals(picking.getBranchId())) {
            throw conflict("PICKING_SOURCE_CONFLICT", "El Picking no coincide con su pedido.");
        }
    }

    private static void requireOrderSource(PickingOrder picking) {
        if (picking.getSourceType() != PickingSourceType.order || picking.getOrderId() == null) {
            throw conflict(
                    "PICKING_SOURCE_NOT_SUPPORTED",
                    "La fuente de Picking solicitada aun no está soportada.");
        }
    }

    private static void requireEligibleOrderSource(Order order) {
        if (order.getSource() != OrderSource.ecommerce
                || order.getDeliveryMethod() != DeliveryMethod.home_delivery) {
            throw conflict(
                    "PICKING_ORDER_NOT_ELIGIBLE",
                    "Solo los pedidos ecommerce con entrega a domicilio admiten Picking.");
        }
    }

    private static void requireMutableOrderPicking(PickingOrder picking, Order order) {
        requireOrderSource(picking);
        requireNonTerminal(picking);
        if (order.getSource() != OrderSource.ecommerce
                || order.getDeliveryMethod() != DeliveryMethod.home_delivery) {
            throw conflict("PICKING_ORDER_NOT_ELIGIBLE", "El pedido no admite Picking.");
        }
    }

    private static void requireNonTerminal(PickingOrder picking) {
        if (picking.getStatus() == PickingStatus.completed
                || picking.getStatus() == PickingStatus.cancelled) {
            throw conflict("INVALID_PICKING_STATE", "El Picking se encuentra en un estado terminal.");
        }
    }

    private static void requireAssignedActor(PickingOrder picking, AuthenticatedUser actor) {
        if (!actor.userId().equals(picking.getAssignedUserId())) {
            throw conflict(
                    "PICKING_NOT_ASSIGNED_TO_ACTOR",
                    "Solo el usuario asignado puede modificar el Picking.");
        }
    }

    private void requireTraceabilitySupported(UUID tenantId, UUID pickingOrderId) {
        List<PickingItem> items =
                pickingItemRepository.findByTenantIdAndPickingOrderId(tenantId, pickingOrderId);
        Map<UUID, Product> products = productRepository
                .findByTenantIdAndIdIn(tenantId, items.stream().map(PickingItem::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        items.forEach(item -> requireTraceabilitySupported(products.get(item.getProductId())));
    }

    private static void requireTraceabilitySupported(Product product) {
        if (product == null) {
            throw conflict("PICKING_PRODUCT_NOT_FOUND", "Producto de Picking no encontrado.");
        }
        if (Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial())
                || Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw conflict(
                    "TRACEABILITY_NOT_SUPPORTED",
                    "Picking de productos con lote, serie o vencimiento aun no está soportado.");
        }
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> notFound("PICKING_PRODUCT_NOT_FOUND", "Producto de Picking no encontrado."));
    }

    private void applyLocation(UUID tenantId, UUID branchId, PickingItem item, UUID requestedLocationId) {
        if (requestedLocationId == null) return;
        if (item.getLocationId() != null && !item.getLocationId().equals(requestedLocationId)) {
            throw conflict(
                    "PICKING_LOCATION_CHANGE_NOT_SUPPORTED",
                    "No se permite cambiar la ubicación reservada de la linea.");
        }
        Location location = locationRepository.findByTenantIdAndId(tenantId, requestedLocationId)
                .filter(found -> branchId.equals(found.getBranchId()))
                .filter(found -> found.getStatus() == LocationStatus.active)
                .orElseThrow(() -> notFound("PICKING_LOCATION_NOT_FOUND", "Ubicación no encontrada."));
        item.setLocationId(location.getId());
    }

    private boolean hasProgress(UUID tenantId, UUID branchId, UUID pickingOrderId) {
        return pickingItemRepository.findByScopeAndPickingOrderId(tenantId, branchId, pickingOrderId)
                .stream()
                .anyMatch(item -> item.getPickedQuantity().signum() > 0);
    }

    private PickingQueueResponse queueResponse(UUID tenantId, PickingOrder picking) {
        Order order = orderRepository.findByTenantIdAndId(tenantId, picking.getOrderId())
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        requireMatchingOrder(picking, order);
        requireEligibleOrderSource(order);
        List<PickingItem> items = pickingItemRepository.findByScopeAndPickingOrderId(
                tenantId, picking.getBranchId(), picking.getId());
        return new PickingQueueResponse(
                picking.getId(),
                order.getId(),
                order.getOrderNumber(),
                customerName(tenantId, order),
                null,
                order.getDeliveryMethod(),
                picking.getBranchId(),
                picking.getStatus(),
                picking.getPriority(),
                picking.getAssignedUserId(),
                progress(items),
                picking.getStartedAt(),
                picking.getCreatedAt(),
                picking.getUpdatedAt());
    }

    private PickingDetailResponse detail(UUID tenantId, PickingOrder picking) {
        Order order = orderRepository.findByTenantIdAndId(tenantId, picking.getOrderId())
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        requireMatchingOrder(picking, order);
        requireEligibleOrderSource(order);
        List<PickingItem> items = pickingItemRepository.findByScopeAndPickingOrderId(
                tenantId, picking.getBranchId(), picking.getId());
        Map<UUID, Product> products = productRepository
                .findByTenantIdAndIdIn(tenantId, items.stream().map(PickingItem::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<PickingLineResponse> lines = items.stream()
                .map(item -> lineResponse(
                        tenantId,
                        picking.getBranchId(),
                        order.getId(),
                        item,
                        products.get(item.getProductId())))
                .toList();
        List<PickingIncidentResponse> incidents = pickingIncidentRepository
                .findByTenantIdAndBranchIdAndPickingOrderIdOrderByCreatedAtAsc(
                        tenantId, picking.getBranchId(), picking.getId())
                .stream()
                .map(PickingIncidentResponse::from)
                .toList();
        List<PickingReleaseResponse> releases = releaseRepository
                .findByTenantIdAndBranchIdAndPickingOrderIdOrderByReleasedAtAsc(
                        tenantId, picking.getBranchId(), picking.getId())
                .stream()
                .map(PickingReleaseResponse::from)
                .toList();
        return new PickingDetailResponse(
                picking.getId(),
                order.getId(),
                order.getOrderNumber(),
                customerName(tenantId, order),
                null,
                order.getDeliveryMethod(),
                picking.getSourceType(),
                picking.getSourceId(),
                picking.getBranchId(),
                picking.getStatus(),
                picking.getPriority(),
                picking.getAssignedUserId(),
                progress(items),
                picking.getStartedAt(),
                picking.getCompletedAt(),
                picking.getCreatedAt(),
                picking.getUpdatedAt(),
                lines,
                incidents,
                releases);
    }

    private PickingLineResponse lineResponse(
            UUID tenantId, UUID branchId, UUID orderId, PickingItem item, Product product) {
        if (product == null) {
            throw conflict("PICKING_PRODUCT_NOT_FOUND", "Producto de Picking no encontrado.");
        }
        PickingLineResponse.Location location = item.getLocationId() == null
                ? null
                : locationRepository.findByTenantIdAndId(tenantId, item.getLocationId())
                        .filter(found -> branchId.equals(found.getBranchId()))
                        .filter(found -> found.getStatus() == LocationStatus.active)
                        .map(found -> new PickingLineResponse.Location(
                                found.getId(), found.getCode(), found.getName()))
                        .orElse(null);
        PickingLineResponse.InventoryAvailability inventory =
                inventoryAvailability(tenantId, branchId, orderId, item);
        List<PickingLineResponse.AvailableLocation> availableLocations = inventory.locations().stream()
                .map(value -> new PickingLineResponse.AvailableLocation(
                        value.locationId(),
                        value.locationCode(),
                        value.locationName(),
                        value.ownReservedQuantity(),
                        value.usableQuantity()))
                .toList();
        return new PickingLineResponse(
                item.getId(),
                item.getOrderItemId(),
                item.getProductId(),
                product.getSku(),
                product.getName(),
                item.getRequestedQuantity(),
                item.getPickedQuantity(),
                item.getRequestedQuantity().subtract(item.getPickedQuantity()),
                item.getStatus(),
                location,
                null,
                List.of(),
                availableLocations,
                List.of(),
                List.of(),
                new PickingLineResponse.Tracking(
                        Boolean.TRUE.equals(product.getTrackingStock()),
                        Boolean.TRUE.equals(product.getTrackingLot()),
                        Boolean.TRUE.equals(product.getTrackingExpiration()),
                        Boolean.TRUE.equals(product.getTrackingSerial())),
                inventory);
    }

    private PickingLineResponse.InventoryAvailability inventoryAvailability(
            UUID tenantId, UUID branchId, UUID orderId, PickingItem item) {
        List<InventoryBalance> balances = inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, item.getProductId());
        InventoryReservation reservation = item.getOrderItemId() == null
                ? null
                : reservationRepository
                        .findByTenantIdAndSourceTypeAndSourceLineIdAndStatus(
                                tenantId,
                                InventoryReservationSourceType.order,
                                item.getOrderItemId(),
                                InventoryReservationStatus.active)
                        .filter(found -> branchId.equals(found.getBranchId()))
                        .filter(found -> orderId.equals(found.getOrderId()))
                        .filter(found -> item.getProductId().equals(found.getProductId()))
                        .orElse(null);
        Map<UUID, BigDecimal> ownReservationByBalance = new HashMap<>();
        if (reservation != null) {
            List<ReservationAllocation> allocations =
                    jsonMapper.readValue(reservation.getAllocations(), ALLOCATIONS_TYPE);
            for (ReservationAllocation allocation : allocations) {
                if (allocation.balanceId() == null || allocation.reservedQuantity() == null) continue;
                BigDecimal consumed = allocation.consumedQuantity() == null
                        ? BigDecimal.ZERO
                        : allocation.consumedQuantity();
                BigDecimal remaining = allocation.reservedQuantity().subtract(consumed).max(BigDecimal.ZERO);
                ownReservationByBalance.merge(allocation.balanceId(), remaining, BigDecimal::add);
            }
        }
        List<PickingLineResponse.InventoryLocation> locations = balances.stream()
                .filter(balance -> availableBalanceLocation(tenantId, branchId, balance))
                .sorted(Comparator.comparing(balance ->
                        balance.getLocationId() == null ? "" : balance.getLocationId().toString()))
                .map(balance -> inventoryLocation(
                        tenantId, branchId, balance, ownReservationByBalance.get(balance.getId())))
                .toList();
        BigDecimal physical = sum(locations, PickingLineResponse.InventoryLocation::physicalQuantity);
        BigDecimal ownReserved = sum(
                locations, PickingLineResponse.InventoryLocation::ownReservedQuantity);
        BigDecimal otherReserved = sum(
                locations, PickingLineResponse.InventoryLocation::otherReservedQuantity);
        BigDecimal free = sum(locations, PickingLineResponse.InventoryLocation::freeQuantity);
        BigDecimal usable = sum(locations, PickingLineResponse.InventoryLocation::usableQuantity);
        return new PickingLineResponse.InventoryAvailability(
                tenantId,
                branchId,
                item.getPickingOrderId(),
                orderId,
                item.getProductId(),
                physical,
                ownReserved,
                otherReserved,
                free,
                usable,
                locations);
    }

    private PickingLineResponse.InventoryLocation inventoryLocation(
            UUID tenantId,
            UUID branchId,
            InventoryBalance balance,
            BigDecimal ownReservation) {
        BigDecimal physical = balance.getQuantity().max(BigDecimal.ZERO);
        BigDecimal ownReserved = ownReservation == null ? BigDecimal.ZERO : ownReservation;
        BigDecimal otherReserved = balance.getReservedQuantity()
                .subtract(ownReserved)
                .max(BigDecimal.ZERO);
        BigDecimal free = physical.subtract(balance.getReservedQuantity()).max(BigDecimal.ZERO);
        BigDecimal usable = physical.min(ownReserved.add(free));
        Location location = balance.getLocationId() == null
                ? null
                : locationRepository.findByTenantIdAndId(tenantId, balance.getLocationId())
                        .filter(found -> branchId.equals(found.getBranchId()))
                        .filter(found -> found.getStatus() == LocationStatus.active)
                        .orElse(null);
        return new PickingLineResponse.InventoryLocation(
                balance.getId(),
                balance.getLocationId(),
                location == null ? null : location.getCode(),
                location == null ? null : location.getName(),
                physical,
                ownReserved,
                otherReserved,
                free,
                usable,
                List.of(),
                List.of());
    }

    private boolean availableBalanceLocation(
            UUID tenantId, UUID branchId, InventoryBalance balance) {
        if (balance.getLocationId() == null) return true;
        return locationRepository.findByTenantIdAndId(tenantId, balance.getLocationId())
                .filter(location -> branchId.equals(location.getBranchId()))
                .filter(location -> location.getStatus() == LocationStatus.active)
                .isPresent();
    }

    private static BigDecimal sum(
            List<PickingLineResponse.InventoryLocation> locations,
            Function<PickingLineResponse.InventoryLocation, BigDecimal> value) {
        return locations.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String customerName(UUID tenantId, Order order) {
        if (order.getCustomerId() != null) {
            return customerRepository.findByTenantIdAndId(tenantId, order.getCustomerId())
                    .map(Customer::getName)
                    .orElse("Cliente");
        }
        if (order.getGuestCustomer() == null) return "Cliente invitado";
        Map<String, Object> guest = jsonMapper.readValue(
                order.getGuestCustomer(), new TypeReference<Map<String, Object>>() {});
        Object name = guest.get("name");
        return name instanceof String value && !value.isBlank() ? value : "Cliente invitado";
    }

    private static PickingProgressResponse progress(List<PickingItem> items) {
        BigDecimal required = items.stream()
                .map(PickingItem::getRequestedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal picked = items.stream()
                .map(PickingItem::getPickedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int percentage = required.signum() == 0
                ? 0
                : picked.multiply(BigDecimal.valueOf(100))
                        .divide(required, 0, RoundingMode.DOWN)
                        .intValue();
        return new PickingProgressResponse(required, picked, required.subtract(picked), percentage);
    }

    private static PickingItemStatus itemStatus(BigDecimal picked, BigDecimal requested) {
        if (picked.signum() == 0) return PickingItemStatus.pending;
        if (picked.compareTo(requested) == 0) return PickingItemStatus.completed;
        return PickingItemStatus.partial;
    }

    private static PickingActionResponse action(
            PickingOrder picking, Order order, boolean idempotent) {
        return new PickingActionResponse(
                picking.getId(),
                order.getId(),
                picking.getStatus(),
                picking.getAssignedUserId(),
                order.getStatus(),
                picking.getUpdatedAt(),
                idempotent);
    }

    private UUID firstLocation(InventoryReservation reservation) {
        if (reservation == null) return null;
        List<ReservationAllocation> allocations =
                jsonMapper.readValue(reservation.getAllocations(), ALLOCATIONS_TYPE);
        return allocations.stream()
                .map(ReservationAllocation::locationId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String fingerprint(
            UUID itemId, UpdatePickingItemRequest request, UUID actorUserId) {
        String payload = itemId
                + "|" + request.pickedQuantity().stripTrailingZeros().toPlainString()
                + "|" + String.valueOf(request.locationId())
                + "|" + actorUserId;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }

    private record ReservationKey(UUID sourceLineId, UUID productId) {}

    private record ReservationAllocation(
            UUID id,
            UUID balanceId,
            UUID locationId,
            BigDecimal reservedQuantity,
            BigDecimal consumedQuantity) {}
}
