package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.DashboardSummaryResponse;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.DailySaleDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.IncidentAnalyticsDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.PendingOrdersByBranchDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.PendingOrdersByStatusDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.SalesByBranchDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.SalesSummaryDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.StockAlertsByBranchDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.StockAlertsDto;
import com.omniretail.backend.administration.dto.DashboardSummaryResponse.TopProductDto;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.purchasing.repository.ReceiptIncidentRepository;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.text.Collator;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resumen ejecutivo del dashboard de administración. Mismas reglas que GetDashboardSummaryService.ts,
 * pero con fechas en la zona horaria del tenant (el frontend usa la hora local del navegador).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardAdminService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Guatemala");
    private static final Locale SPANISH = Locale.of("es");
    private static final List<OrderStatus> PENDING_LOGISTICS_STATUSES = List.of(
            OrderStatus.confirmed,
            OrderStatus.preparing,
            OrderStatus.picking,
            OrderStatus.packing,
            OrderStatus.ready_for_dispatch);
    private static final int TOP_PRODUCTS_LIMIT = 10;

    private final CurrentUser currentUser;
    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final OrderRepository orderRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final ProductRepository productRepository;
    private final ReceiptIncidentRepository receiptIncidentRepository;

    public DashboardSummaryResponse getDashboardSummary() {
        UUID tenantId = currentUser.require().tenantId();
        ZoneId zoneId = resolveZone(tenantId);
        LocalDate today = ZonedDateTime.now(zoneId).toLocalDate();
        YearMonth currentMonth = YearMonth.from(today);
        Collator collator = Collator.getInstance(SPANISH);
        List<Branch> branches = branchRepository.findByTenantIdAndStatus(tenantId, BranchStatus.active).stream()
                .sorted(Comparator.comparing(Branch::getName, collator))
                .toList();

        Instant monthStart = currentMonth.atDay(1).atStartOfDay(zoneId).toInstant();
        Instant nextMonthStart = currentMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant();

        // Solo se leen las ventas desde el inicio del mes, no todo el historial del tenant.
        List<Sale> monthSales = saleRepository
                .findByTenantIdAndStatusNotAndCreatedAtGreaterThanEqual(tenantId, SaleStatus.cancelled, monthStart)
                .stream()
                .filter(sale -> YearMonth.from(localDate(sale, zoneId)).equals(currentMonth))
                .toList();
        List<Sale> todaySales = monthSales.stream()
                .filter(sale -> localDate(sale, zoneId).isEqual(today))
                .toList();

        List<Order> pendingOrders = orderRepository.findByTenantIdAndStatusIn(tenantId, PENDING_LOGISTICS_STATUSES);
        List<StockAlertsByBranchDto> stockAlertsByBranch = stockAlertsByBranch(tenantId, branches);

        return new DashboardSummaryResponse(
                summarize(todaySales),
                summarize(monthSales),
                salesByBranch(monthSales, branches, collator),
                dailySalesMonth(monthSales, today, zoneId),
                new StockAlertsDto(
                        stockAlertsByBranch.stream().mapToLong(StockAlertsByBranchDto::outOfStock).sum(),
                        stockAlertsByBranch.stream().mapToLong(StockAlertsByBranchDto::lowStock).sum()),
                stockAlertsByBranch,
                pendingOrders.size(),
                pendingOrdersByStatus(pendingOrders),
                pendingOrdersByBranch(pendingOrders, branches, collator),
                // Solo el total del mes es real. byType/bySupplier y latestIncidents siguen vacíos: sus etiquetas
                // (typeName, supplierName) no tienen contrato acordado con el frontend, y latestIncidents también
                // espera incidencias de traslados, que todavía no existen en el backend.
                new IncidentAnalyticsDto(
                        receiptIncidentRepository.countByTenantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                                tenantId, monthStart, nextMonthStart),
                        List.of(),
                        List.of()),
                List.of(),
                topProducts(monthSales, collator));
    }

    private ZoneId resolveZone(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(Tenant::getTimezone)
                .map(timezone -> {
                    try {
                        return ZoneId.of(timezone);
                    } catch (DateTimeException invalidTimezone) {
                        return DEFAULT_ZONE;
                    }
                })
                .orElse(DEFAULT_ZONE);
    }

    private static LocalDate localDate(Sale sale, ZoneId zoneId) {
        return sale.getCreatedAt().atZone(zoneId).toLocalDate();
    }

    private static SalesSummaryDto summarize(List<Sale> sales) {
        return new SalesSummaryDto(sumTotals(sales), sales.size());
    }

    private static BigDecimal sumTotals(List<Sale> sales) {
        return sales.stream().map(Sale::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Todas las sucursales activas, aunque no tengan ventas; mayor monto primero, desempate alfabético.
    private static List<SalesByBranchDto> salesByBranch(
            List<Sale> monthSales, List<Branch> branches, Collator collator) {
        Map<UUID, List<Sale>> salesByBranchId = monthSales.stream().collect(Collectors.groupingBy(Sale::getBranchId));
        return branches.stream()
                .map(branch -> {
                    List<Sale> branchSales = salesByBranchId.getOrDefault(branch.getId(), List.of());
                    return new SalesByBranchDto(branch.getName(), sumTotals(branchSales), branchSales.size());
                })
                .sorted(Comparator.comparing(SalesByBranchDto::amount)
                        .reversed()
                        .thenComparing(SalesByBranchDto::branchName, collator))
                .toList();
    }

    // Un punto por día, del 1 hasta hoy, incluidos los días sin ventas.
    private static List<DailySaleDto> dailySalesMonth(List<Sale> monthSales, LocalDate today, ZoneId zoneId) {
        Map<LocalDate, List<Sale>> salesByDay =
                monthSales.stream().collect(Collectors.groupingBy(sale -> localDate(sale, zoneId)));
        List<DailySaleDto> days = new ArrayList<>();
        for (int day = 1; day <= today.getDayOfMonth(); day++) {
            LocalDate date = today.withDayOfMonth(day);
            List<Sale> daySales = salesByDay.getOrDefault(date, List.of());
            days.add(new DailySaleDto(date.toString(), sumTotals(daySales), daySales.size()));
        }
        return days;
    }

    /**
     * Misma regla que GetInventoryAlertsService.ts: por cada sucursal activa y cada producto publicado
     * que controla stock, el disponible es la suma de max(cantidad - reservado, 0) de sus balances.
     * Disponible 0 = sin stock, aunque el producto no tenga ningún balance en esa sucursal. lowStock es
     * 0 porque el backend todavía no guarda stock mínimo por producto y sucursal.
     */
    private List<StockAlertsByBranchDto> stockAlertsByBranch(UUID tenantId, List<Branch> branches) {
        List<Product> stockProducts =
                productRepository.findByTenantIdAndStatusAndTrackingStockTrue(tenantId, ProductStatus.published);
        Map<UUID, Map<UUID, BigDecimal>> availableByBranchAndProduct = new HashMap<>();
        for (InventoryBalance balance : inventoryBalanceRepository.findByTenantId(tenantId)) {
            BigDecimal available = balance.getQuantity().subtract(balance.getReservedQuantity()).max(BigDecimal.ZERO);
            availableByBranchAndProduct
                    .computeIfAbsent(balance.getBranchId(), branchId -> new HashMap<>())
                    .merge(balance.getProductId(), available, BigDecimal::add);
        }

        return branches.stream()
                .map(branch -> {
                    Map<UUID, BigDecimal> available =
                            availableByBranchAndProduct.getOrDefault(branch.getId(), Map.of());
                    long outOfStock = stockProducts.stream()
                            .filter(product -> available.getOrDefault(product.getId(), BigDecimal.ZERO).signum() == 0)
                            .count();
                    long lowStock = 0;
                    return new StockAlertsByBranchDto(
                            branch.getId(), branch.getName(), outOfStock, lowStock, outOfStock + lowStock);
                })
                .toList();
    }

    private static PendingOrdersByStatusDto pendingOrdersByStatus(List<Order> pendingOrders) {
        Map<OrderStatus, Long> counts =
                pendingOrders.stream().collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));
        return new PendingOrdersByStatusDto(
                counts.getOrDefault(OrderStatus.confirmed, 0L),
                counts.getOrDefault(OrderStatus.preparing, 0L),
                counts.getOrDefault(OrderStatus.picking, 0L),
                counts.getOrDefault(OrderStatus.packing, 0L),
                counts.getOrDefault(OrderStatus.ready_for_dispatch, 0L));
    }

    private static List<PendingOrdersByBranchDto> pendingOrdersByBranch(
            List<Order> pendingOrders, List<Branch> branches, Collator collator) {
        Map<UUID, Long> countsByBranchId =
                pendingOrders.stream().collect(Collectors.groupingBy(Order::getBranchId, Collectors.counting()));
        return branches.stream()
                .map(branch -> new PendingOrdersByBranchDto(
                        branch.getName(), countsByBranchId.getOrDefault(branch.getId(), 0L)))
                .sorted(Comparator.comparingLong(PendingOrdersByBranchDto::count)
                        .reversed()
                        .thenComparing(PendingOrdersByBranchDto::branchName, collator))
                .toList();
    }

    private List<TopProductDto> topProducts(List<Sale> monthSales, Collator collator) {
        if (monthSales.isEmpty()) {
            return List.of();
        }
        List<UUID> saleIds = monthSales.stream().map(Sale::getId).toList();
        Map<String, BigDecimal> quantities = new HashMap<>();
        Map<String, BigDecimal> revenues = new HashMap<>();
        for (SaleItem item : saleItemRepository.findBySaleIdIn(saleIds)) {
            quantities.merge(item.getNameSnapshot(), item.getQuantity(), BigDecimal::add);
            revenues.merge(item.getNameSnapshot(), item.getSubtotal(), BigDecimal::add);
        }
        return quantities.entrySet().stream()
                .map(entry -> new TopProductDto(entry.getKey(), entry.getValue(), revenues.get(entry.getKey())))
                .sorted(Comparator.comparing(TopProductDto::totalQuantity)
                        .reversed()
                        .thenComparing(TopProductDto::productName, collator))
                .limit(TOP_PRODUCTS_LIMIT)
                .toList();
    }
}
