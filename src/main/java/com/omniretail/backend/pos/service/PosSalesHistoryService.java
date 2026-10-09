package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.ecommerce.service.OrderCustomerNameResolver;
import com.omniretail.backend.pos.dto.PosSalesHistoryPageResponse;
import com.omniretail.backend.pos.dto.PosSalesHistoryRowResponse;
import com.omniretail.backend.pos.dto.PosSalesHistorySummaryResponse;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.pos.repository.SaleSpecifications;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PosSalesHistoryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Guatemala");
    private static final String FINAL_CUSTOMER = "Consumidor final";
    private static final Set<String> SUPPORTED_SORTS = Set.of("createdAt", "id");

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard capability;
    private final BranchAccessResolver branchAccess;
    private final BranchRepository branches;
    private final TenantRepository tenants;
    private final SaleRepository sales;
    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final OrderCustomerNameResolver customerNameResolver;

    public PosSalesHistoryPageResponse search(
            UUID branchId,
            String search,
            LocalDate from,
            LocalDate to,
            SaleStatus status,
            DeliveryMethod deliveryMethod,
            OrderStatus operationalStatus,
            Pageable requestedPageable) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        var access = branchAccess.resolve(actor);
        branches.findByTenantIdAndId(actor.tenantId(), branchId)
                .filter(branch -> access.allows(branch.getId()))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SALE_DATE_RANGE",
                    "La fecha inicial no puede ser posterior a la fecha final.");
        }

        ZoneId zone = tenantZone(actor.tenantId());
        Instant fromInclusive = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        Specification<Sale> filters = SaleSpecifications.history(
                actor.tenantId(),
                branchId,
                search,
                fromInclusive,
                toExclusive,
                status,
                deliveryMethod,
                operationalStatus);
        Pageable pageable = safePageable(requestedPageable);
        Page<Sale> page = sales.findAll(filters, pageable);

        Map<UUID, Order> ordersById = ordersById(actor.tenantId(), branchId, page.getContent());
        Map<UUID, Customer> customersById = customersById(actor.tenantId(), page.getContent());
        List<PosSalesHistoryRowResponse> rows = page.getContent().stream()
                .map(sale -> row(sale, ordersById.get(sale.getSourceOrderId()), customersById))
                .toList();
        PosSalesHistorySummaryResponse summary = summary(filters);
        return PosSalesHistoryPageResponse.from(page, rows, summary);
    }

    private PosSalesHistorySummaryResponse summary(Specification<Sale> filters) {
        long completed = count(filters, SaleStatus.completed);
        long partiallyReturned = count(filters, SaleStatus.partially_returned);
        long returned = count(filters, SaleStatus.returned);
        long cancelled = count(filters, SaleStatus.cancelled);
        return new PosSalesHistorySummaryResponse(
                completed + partiallyReturned + returned + cancelled,
                completed,
                partiallyReturned,
                returned,
                cancelled);
    }

    private long count(Specification<Sale> filters, SaleStatus status) {
        return sales.count(filters.and(SaleSpecifications.status(status)));
    }

    private PosSalesHistoryRowResponse row(
            Sale sale,
            Order order,
            Map<UUID, Customer> customersById) {
        DeliveryMethod deliveryMethod = sale.getSourceOrderId() == null
                ? DeliveryMethod.immediate
                : order == null ? null : order.getDeliveryMethod();
        return new PosSalesHistoryRowResponse(
                sale.getId(),
                sale.getNumber(),
                sale.getCreatedAt(),
                customerDisplayName(sale, order, customersById),
                sale.getTotal(),
                sale.getStatus(),
                sale.getSourceOrderId(),
                deliveryMethod,
                order == null ? null : order.getStatus());
    }

    private String customerDisplayName(
            Sale sale,
            Order order,
            Map<UUID, Customer> customersById) {
        if (sale.getDocumentLegalName() != null && !sale.getDocumentLegalName().isBlank()) {
            return sale.getDocumentLegalName();
        }
        Customer customer = customersById.get(sale.getCustomerId());
        return customerNameResolver.resolve(
                order, customer == null ? null : customer.getName(), FINAL_CUSTOMER);
    }

    private Map<UUID, Order> ordersById(UUID tenantId, UUID branchId, List<Sale> page) {
        Set<UUID> ids = page.stream()
                .map(Sale::getSourceOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return index(
                ids.isEmpty()
                        ? List.of()
                        : orders.findByTenantIdAndBranchIdAndIdIn(tenantId, branchId, ids),
                Order::getId);
    }

    private Map<UUID, Customer> customersById(UUID tenantId, List<Sale> page) {
        Set<UUID> ids = page.stream()
                .map(Sale::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return index(
                ids.isEmpty() ? List.of() : customers.findByTenantIdAndIdIn(tenantId, ids),
                Customer::getId);
    }

    private ZoneId tenantZone(UUID tenantId) {
        return tenants.findById(tenantId)
                .map(Tenant::getTimezone)
                .map(PosSalesHistoryService::zoneId)
                .orElse(DEFAULT_ZONE);
    }

    private static ZoneId zoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            return DEFAULT_ZONE;
        }
    }

    private static Pageable safePageable(Pageable requested) {
        requested.getSort().forEach(order -> {
            if (!SUPPORTED_SORTS.contains(order.getProperty())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "POS_SALES_HISTORY_SORT_INVALID",
                        "El historial de ventas no admite el campo de ordenamiento solicitado.");
            }
        });
        Sort sort = requested.getSort().isUnsorted()
                ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                : stableSort(requested.getSort());
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                Math.min(Math.max(requested.getPageSize(), 1), 100),
                sort);
    }

    private static Sort stableSort(Sort requested) {
        boolean hasId = requested.stream().anyMatch(order -> order.getProperty().equals("id"));
        if (hasId) return requested;
        Sort.Direction direction = requested.getOrderFor("createdAt") == null
                ? Sort.Direction.DESC
                : requested.getOrderFor("createdAt").getDirection();
        return requested.and(Sort.by(new Sort.Order(direction, "id")));
    }

    private static <T> Map<UUID, T> index(Collection<T> values, Function<T, UUID> id) {
        return values.stream().collect(Collectors.toMap(id, Function.identity(), (first, second) -> first));
    }
}
