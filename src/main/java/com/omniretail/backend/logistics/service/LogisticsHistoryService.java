package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.ecommerce.service.OrderCustomerNameResolver;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.logistics.dto.DispatchPackageResponse;
import com.omniretail.backend.logistics.dto.LogisticsHistoryDeliveryMethod;
import com.omniretail.backend.logistics.dto.LogisticsHistoryDetailResponse;
import com.omniretail.backend.logistics.dto.LogisticsHistoryLineResponse;
import com.omniretail.backend.logistics.dto.LogisticsHistoryRowResponse;
import com.omniretail.backend.logistics.dto.PhysicalTraceSelectionResponse;
import com.omniretail.backend.logistics.entity.Dispatch;
import com.omniretail.backend.logistics.entity.DispatchPackage;
import com.omniretail.backend.logistics.entity.DispatchSourceType;
import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.repository.DispatchPackageRepository;
import com.omniretail.backend.logistics.repository.DispatchRepository;
import com.omniretail.backend.logistics.repository.LogisticsHistorySpecifications;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogisticsHistoryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Guatemala");
    private static final String GUEST_CUSTOMER = "Cliente invitado";
    private static final int MAX_PAGE_SIZE = 100;

    private final PickingOrderRepository pickingOrders;
    private final PickingItemRepository pickingItems;
    private final PackingRepository packings;
    private final DispatchRepository dispatches;
    private final DispatchPackageRepository dispatchPackages;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final CustomerRepository customers;
    private final InventoryTransferRepository transfers;
    private final InventoryTransferItemRepository transferItems;
    private final ProductRepository products;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final PickingTraceProjectionService traceProjection;
    private final OrderCustomerNameResolver customerNameResolver;
    private final CurrentUser currentUser;
    private final BranchAccessResolver branchAccessResolver;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final JsonMapper jsonMapper;

    public PageResponse<LogisticsHistoryRowResponse> search(
            UUID branchId,
            String search,
            String status,
            String deliveryMethod,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        AuthenticatedUser actor = actorForBranch(branchId);
        if (from != null && to != null && from.isAfter(to)) {
            throw badRequest(
                    "INVALID_LOGISTICS_HISTORY_DATE_RANGE",
                    "La fecha inicial no puede ser posterior a la fecha final.");
        }
        StatusFilter statusFilter = statusFilter(status);
        LogisticsHistoryDeliveryMethod deliveryFilter = deliveryMethod(deliveryMethod);
        ZoneId zone = tenantZone(actor.tenantId());
        Instant fromInclusive = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<PickingOrder> result = pickingOrders.findAll(
                LogisticsHistorySpecifications.history(
                        actor.tenantId(),
                        branchId,
                        search,
                        statusFilter.orderStatus(),
                        statusFilter.transferStatus(),
                        statusFilter.specified(),
                        deliveryFilter,
                        fromInclusive,
                        toExclusive),
                pageable);
        HistoryData data = load(actor.tenantId(), branchId, result.getContent(), false);
        List<LogisticsHistoryRowResponse> rows = result.getContent().stream()
                .map(picking -> row(picking, data))
                .toList();
        return new PageResponse<>(
                rows,
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    public LogisticsHistoryDetailResponse detail(
            UUID branchId, PickingSourceType sourceType, UUID sourceId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        PickingOrder picking = pickingOrders
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        actor.tenantId(), branchId, sourceType, sourceId)
                .orElseThrow(() -> notFound(
                        "LOGISTICS_HISTORY_NOT_FOUND", "Historial logístico no encontrado."));
        HistoryData data = load(actor.tenantId(), branchId, List.of(picking), true);
        LogisticsHistoryRowResponse summary = row(picking, data);
        List<LogisticsHistoryLineResponse> lines = data.itemsByPicking()
                .getOrDefault(picking.getId(), List.of())
                .stream()
                .map(item -> line(picking, item, data))
                .toList();
        List<DispatchPackageResponse> packages = summary.dispatchId() == null
                ? List.of()
                : data.packagesByDispatch()
                        .getOrDefault(summary.dispatchId(), List.of())
                        .stream()
                        .map(LogisticsHistoryService::packageResponse)
                        .toList();
        return new LogisticsHistoryDetailResponse(summary, lines, packages);
    }

    private HistoryData load(
            UUID tenantId,
            UUID branchId,
            List<PickingOrder> page,
            boolean includeDetail) {
        if (page.isEmpty()) {
            return HistoryData.empty();
        }
        Set<UUID> orderIds = sourceIds(page, PickingSourceType.order);
        Set<UUID> transferIds = sourceIds(page, PickingSourceType.transfer);
        Map<UUID, Order> orderMap = index(
                orderIds.isEmpty()
                        ? List.of()
                        : orders.findByTenantIdAndBranchIdAndIdIn(tenantId, branchId, orderIds),
                Order::getId);
        Map<UUID, InventoryTransfer> transferMap = index(
                transferIds.isEmpty() ? List.of() : transfers.findByTenantIdAndIdIn(tenantId, transferIds),
                InventoryTransfer::getId);
        transferMap.values().removeIf(transfer -> !branchId.equals(transfer.getSourceBranchId()));

        List<UUID> pickingIds = page.stream().map(PickingOrder::getId).toList();
        List<PickingItem> allItems = includeDetail
                ? pickingItems.findByTenantIdAndPickingOrderIdInOrderByPickingOrderIdAscCreatedAtAsc(
                        tenantId, pickingIds)
                : List.of();
        Map<UUID, List<PickingItem>> itemsByPicking = allItems.stream()
                .collect(Collectors.groupingBy(
                        PickingItem::getPickingOrderId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Packing> packingByPicking = index(
                packings.findByTenantIdAndBranchIdAndPickingOrderIdIn(
                        tenantId, branchId, pickingIds),
                Packing::getPickingOrderId);

        Map<SourceKey, Dispatch> dispatchBySource = new HashMap<>();
        loadDispatches(tenantId, branchId, DispatchSourceType.order, orderIds, dispatchBySource);
        loadDispatches(tenantId, branchId, DispatchSourceType.transfer, transferIds, dispatchBySource);
        List<UUID> dispatchIds = dispatchBySource.values().stream().map(Dispatch::getId).toList();
        Map<UUID, List<DispatchPackage>> packagesByDispatch =
                !includeDetail || dispatchIds.isEmpty()
                ? Map.of()
                : dispatchPackages.findByDispatchIdInOrderByDispatchIdAscNumberAsc(dispatchIds)
                        .stream()
                        .collect(Collectors.groupingBy(
                                DispatchPackage::getDispatchId,
                                LinkedHashMap::new,
                                Collectors.toList()));

        Map<UUID, Customer> customerMap = index(
                customerIds(orderMap).isEmpty()
                        ? List.of()
                        : customers.findByTenantIdAndIdIn(tenantId, customerIds(orderMap)),
                Customer::getId);
        Map<UUID, Product> productMap = index(
                !includeDetail || allItems.isEmpty()
                        ? List.of()
                        : products.findByTenantIdAndIdIn(
                                tenantId,
                                allItems.stream()
                                        .map(PickingItem::getProductId)
                                        .collect(Collectors.toSet())),
                Product::getId);
        Map<UUID, List<PhysicalTraceSelectionResponse>> tracesByItem =
                !includeDetail || allItems.isEmpty()
                ? Map.of()
                : traceProjection.projectAll(tenantId, allItems, productMap);

        Map<UUID, OrderItem> orderItemMap = index(
                !includeDetail || orderIds.isEmpty()
                        ? List.of()
                        : orderItems.findByOrderIdIn(orderIds),
                OrderItem::getId);
        Map<UUID, InventoryTransferItem> transferItemMap = index(
                !includeDetail || transferIds.isEmpty()
                        ? List.of()
                        : transferItems.findByTenantIdAndTransferIdInOrderByTransferIdAscIdAsc(
                                tenantId, transferIds),
                InventoryTransferItem::getId);
        Set<UUID> userIds = responsibleUserIds(
                page, transferMap, packingByPicking, dispatchBySource);
        Map<UUID, User> userMap = index(
                userIds.isEmpty() ? List.of() : users.findByTenantIdAndIdIn(tenantId, userIds),
                User::getId);
        return new HistoryData(
                orderMap,
                transferMap,
                itemsByPicking,
                packingByPicking,
                dispatchBySource,
                packagesByDispatch,
                customerMap,
                userMap,
                productMap,
                orderItemMap,
                transferItemMap,
                tracesByItem);
    }

    private LogisticsHistoryRowResponse row(PickingOrder picking, HistoryData data) {
        Order order = data.orders().get(picking.getSourceId());
        InventoryTransfer transfer = data.transfers().get(picking.getSourceId());
        requireSource(picking, order, transfer);
        Packing packing = data.packingByPicking().get(picking.getId());
        Dispatch dispatch = data.dispatchBySource().get(new SourceKey(
                DispatchSourceType.valueOf(picking.getSourceType().name()), picking.getSourceId()));
        UUID responsibleUserId = responsibleUserId(picking, transfer, packing, dispatch);
        User responsible = responsibleUserId == null
                ? null
                : data.users().get(responsibleUserId);
        Customer customer = order == null || order.getCustomerId() == null
                ? null
                : data.customers().get(order.getCustomerId());
        return new LogisticsHistoryRowResponse(
                picking.getSourceType(),
                picking.getSourceId(),
                order == null ? null : order.getId(),
                order == null ? transfer.getNumber() : order.getOrderNumber(),
                order == null
                        ? LogisticsHistoryDeliveryMethod.transfer
                        : LogisticsHistoryDeliveryMethod.valueOf(order.getDeliveryMethod().name()),
                order == null ? transfer.getStatus().name() : order.getStatus().name(),
                order == null
                        ? null
                        : customerNameResolver.resolve(
                                order, customer == null ? null : customer.getName(), GUEST_CUSTOMER),
                order == null ? null : contactPhone(order, customer),
                picking.getId(),
                packing == null ? null : packing.getId(),
                dispatch == null ? null : dispatch.getId(),
                null,
                picking.getCompletedAt(),
                packing == null ? null : packing.getFinalizedAt(),
                dispatch == null ? null : dispatch.getDispatchedAt(),
                order == null ? transfer.getReceivedAt() : order.getDeliveredAt(),
                responsibleUserId,
                responsible == null ? null : responsible.getName(),
                packing == null ? null : packing.getTotalWeight(),
                packing == null ? null : packing.getPackageCount(),
                dispatch == null ? null : dispatch.getStatus(),
                dispatch == null ? null : dispatch.getCarrierName(),
                dispatch == null ? null : dispatch.getTrackingNumber());
    }

    private LogisticsHistoryLineResponse line(
            PickingOrder picking, PickingItem item, HistoryData data) {
        Product product = data.products().get(item.getProductId());
        if (product == null) {
            throw BusinessException.conflict(
                    "LOGISTICS_HISTORY_PRODUCT_MISSING",
                    "El producto del historial logístico no existe.");
        }
        Packing packing = data.packingByPicking().get(picking.getId());
        Dispatch dispatch = data.dispatchBySource().get(new SourceKey(
                DispatchSourceType.valueOf(picking.getSourceType().name()), picking.getSourceId()));
        BigDecimal packed = packing != null && packing.getStatus() == PackingStatus.finalized
                ? item.getPickedQuantity()
                : null;
        BigDecimal dispatched = null;
        if (dispatch != null && packed != null) {
            if (picking.getSourceType() == PickingSourceType.transfer) {
                InventoryTransferItem transferItem = data.transferItems().get(item.getSourceLineId());
                dispatched = transferItem == null
                        ? null
                        : minimum(packed, transferItem.getDispatchedQuantity());
            } else {
                dispatched = packed;
            }
        }
        OrderItem orderItem = data.orderItems().get(item.getSourceLineId());
        String productName = orderItem == null ? product.getName() : orderItem.getNameSnapshot();
        return new LogisticsHistoryLineResponse(
                item.getProductId(),
                productName,
                item.getRequestedQuantity(),
                item.getPickedQuantity(),
                packed,
                dispatched,
                data.tracesByItem().getOrDefault(item.getId(), List.of()));
    }

    private AuthenticatedUser actorForBranch(UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.inventory);
        if (branchId == null || !branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        return actor;
    }

    private ZoneId tenantZone(UUID tenantId) {
        return tenants.findById(tenantId)
                .map(Tenant::getTimezone)
                .map(LogisticsHistoryService::zoneId)
                .orElse(DEFAULT_ZONE);
    }

    private static ZoneId zoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            return DEFAULT_ZONE;
        }
    }

    private StatusFilter statusFilter(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return new StatusFilter(null, null, false);
        }
        OrderStatus orderStatus = enumValue(OrderStatus.values(), value);
        InventoryTransferStatus transferStatus = enumValue(InventoryTransferStatus.values(), value);
        if (orderStatus == null && transferStatus == null) {
            throw badRequest(
                    "LOGISTICS_HISTORY_STATUS_INVALID",
                    "El estado solicitado no pertenece a pedidos ni traslados.");
        }
        return new StatusFilter(orderStatus, transferStatus, true);
    }

    private LogisticsHistoryDeliveryMethod deliveryMethod(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        LogisticsHistoryDeliveryMethod result =
                enumValue(LogisticsHistoryDeliveryMethod.values(), value);
        if (result == null) {
            throw badRequest(
                    "LOGISTICS_HISTORY_DELIVERY_METHOD_INVALID",
                    "La modalidad solicitada no es válida.");
        }
        return result;
    }

    private String contactPhone(Order order, Customer customer) {
        String primary = order.getDeliveryMethod() == DeliveryMethod.store_pickup
                ? text(order.getStorePickupContact(), "recipientPhone")
                : order.getDeliveryMethod() == DeliveryMethod.home_delivery
                        ? text(order.getDeliveryAddress(), "recipientPhone")
                        : null;
        if (primary != null) {
            return primary;
        }
        String alternate = order.getDeliveryMethod() == DeliveryMethod.store_pickup
                ? text(order.getDeliveryAddress(), "recipientPhone")
                : text(order.getStorePickupContact(), "recipientPhone");
        if (alternate != null) {
            return alternate;
        }
        String guestPhone = text(order.getGuestCustomer(), "phone");
        return guestPhone != null
                ? guestPhone
                : customer == null ? null : trimToNull(customer.getPhone());
    }

    private String text(String json, String field) {
        if (json == null) {
            return null;
        }
        JsonNode value = jsonMapper.readTree(json).get(field);
        return value == null || value.isNull() ? null : trimToNull(value.asText());
    }

    private void loadDispatches(
            UUID tenantId,
            UUID branchId,
            DispatchSourceType sourceType,
            Collection<UUID> sourceIds,
            Map<SourceKey, Dispatch> destination) {
        if (sourceIds.isEmpty()) {
            return;
        }
        dispatches.findByTenantIdAndBranchIdAndSourceTypeAndSourceIdIn(
                        tenantId, branchId, sourceType, sourceIds)
                .forEach(dispatch -> destination.put(
                        new SourceKey(dispatch.getSourceType(), dispatch.getSourceId()), dispatch));
    }

    private static UUID responsibleUserId(
            PickingOrder picking,
            InventoryTransfer transfer,
            Packing packing,
            Dispatch dispatch) {
        if (transfer != null && transfer.getReceivedAt() != null
                && transfer.getReceivedByUserId() != null) {
            return transfer.getReceivedByUserId();
        }
        if (dispatch != null) {
            return dispatch.getDispatchedByUserId();
        }
        if (packing != null && packing.getFinalizedAt() != null) {
            return packing.getFinalizedByUserId();
        }
        return picking.getAssignedUserId();
    }

    private static Set<UUID> responsibleUserIds(
            List<PickingOrder> pickings,
            Map<UUID, InventoryTransfer> transfers,
            Map<UUID, Packing> packings,
            Map<SourceKey, Dispatch> dispatches) {
        return pickings.stream()
                .map(picking -> responsibleUserId(
                        picking,
                        transfers.get(picking.getSourceId()),
                        packings.get(picking.getId()),
                        dispatches.get(new SourceKey(
                                DispatchSourceType.valueOf(picking.getSourceType().name()),
                                picking.getSourceId()))))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static void requireSource(
            PickingOrder picking, Order order, InventoryTransfer transfer) {
        boolean valid = picking.getSourceType() == PickingSourceType.order
                ? order != null
                : transfer != null;
        if (!valid) {
            throw notFound(
                    "LOGISTICS_HISTORY_NOT_FOUND", "Historial logístico no encontrado.");
        }
    }

    private static Set<UUID> sourceIds(
            List<PickingOrder> pickings, PickingSourceType sourceType) {
        return pickings.stream()
                .filter(picking -> picking.getSourceType() == sourceType)
                .map(PickingOrder::getSourceId)
                .collect(Collectors.toSet());
    }

    private static Set<UUID> customerIds(Map<UUID, Order> orders) {
        return orders.values().stream()
                .map(Order::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static DispatchPackageResponse packageResponse(DispatchPackage value) {
        return new DispatchPackageResponse(
                value.getId(), value.getNumber(), value.getWeight(), value.getDescription());
    }

    private static BigDecimal minimum(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E enumValue(E[] values, String requested) {
        for (E value : values) {
            if (value.name().equalsIgnoreCase(requested)) {
                return value;
            }
        }
        return null;
    }

    private static <T> Map<UUID, T> index(Collection<T> values, Function<T, UUID> id) {
        return values.stream().collect(Collectors.toMap(
                id, Function.identity(), (first, second) -> first, LinkedHashMap::new));
    }

    private static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private record StatusFilter(
            OrderStatus orderStatus,
            InventoryTransferStatus transferStatus,
            boolean specified) {}

    private record SourceKey(DispatchSourceType sourceType, UUID sourceId) {}

    private record HistoryData(
            Map<UUID, Order> orders,
            Map<UUID, InventoryTransfer> transfers,
            Map<UUID, List<PickingItem>> itemsByPicking,
            Map<UUID, Packing> packingByPicking,
            Map<SourceKey, Dispatch> dispatchBySource,
            Map<UUID, List<DispatchPackage>> packagesByDispatch,
            Map<UUID, Customer> customers,
            Map<UUID, User> users,
            Map<UUID, Product> products,
            Map<UUID, OrderItem> orderItems,
            Map<UUID, InventoryTransferItem> transferItems,
            Map<UUID, List<PhysicalTraceSelectionResponse>> tracesByItem) {

        private static HistoryData empty() {
            return new HistoryData(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
