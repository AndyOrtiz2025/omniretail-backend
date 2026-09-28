package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.ReportsDataResponse;
import com.omniretail.backend.administration.dto.ReportsDataResponse.MovementReportRow;
import com.omniretail.backend.administration.dto.ReportsDataResponse.PaymentReportRow;
import com.omniretail.backend.administration.dto.ReportsDataResponse.PurchasesReportRow;
import com.omniretail.backend.administration.dto.ReportsDataResponse.SalesReportRow;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Datos de la pantalla de reportes. Mismos mapeos que ReportMappers.ts y GetReportsService.ts del frontend. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReportAdminService {

    private static final String NO_VALUE = "—";

    private final CurrentUser currentUser;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final OrderRepository orderRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final PaymentRepository paymentRepository;

    public ReportsDataResponse getReportsData() {
        UUID tenantId = currentUser.require().tenantId();
        // Nombres resueltos con una sola consulta por tabla, sin N+1.
        Map<UUID, String> branchNames = branchRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName, (first, second) -> first));
        Map<UUID, String> productNames = productRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName, (first, second) -> first));

        Stream<SalesReportRow> posSales = saleRepository.findByTenantId(tenantId).stream()
                .map(sale -> toSalesRow(sale, branchNames));
        // Los pedidos con origen POS ya están en ventas: se excluyen para no duplicarlos.
        Stream<SalesReportRow> onlineSales = orderRepository.findByTenantId(tenantId).stream()
                .filter(order -> order.getSource() != OrderSource.pos)
                .map(order -> toSalesRow(order, branchNames));
        List<SalesReportRow> sales = Stream.concat(posSales, onlineSales)
                .sorted(Comparator.comparing(SalesReportRow::date).reversed())
                .toList();

        List<MovementReportRow> movements = inventoryMovementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(movement -> toMovementRow(movement, branchNames, productNames))
                .toList();

        List<PaymentReportRow> payments = paymentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(ReportAdminService::toPaymentRow)
                .toList();

        // El módulo de compras todavía no existe en el backend.
        List<PurchasesReportRow> purchases = List.of();

        return new ReportsDataResponse(tenantId, sales, purchases, movements, payments);
    }

    private static SalesReportRow toSalesRow(Sale sale, Map<UUID, String> branchNames) {
        return new SalesReportRow(
                sale.getNumber(),
                sale.getCreatedAt(),
                sale.getBranchId(),
                nameOrId(branchNames, sale.getBranchId()),
                sale.getStatus(),
                sale.getSubtotal(),
                sale.getDiscountTotal(),
                sale.getTaxTotal(),
                sale.getTotal(),
                "POS",
                "Venta física");
    }

    // Un pedido pendiente o cancelado cuenta como venta cancelada; cualquier otro estado, como completada.
    private static SalesReportRow toSalesRow(Order order, Map<UUID, String> branchNames) {
        SaleStatus status = order.getStatus() == OrderStatus.cancelled || order.getStatus() == OrderStatus.pending
                ? SaleStatus.cancelled
                : SaleStatus.completed;
        return new SalesReportRow(
                order.getOrderNumber(),
                order.getCreatedAt(),
                order.getBranchId(),
                nameOrId(branchNames, order.getBranchId()),
                status,
                order.getSubtotal(),
                order.getDiscountTotal(),
                BigDecimal.ZERO,
                order.getTotal(),
                "En línea",
                order.getSource() == OrderSource.ecommerce ? "Tienda en línea" : "App Móvil");
    }

    private static MovementReportRow toMovementRow(
            InventoryMovement movement, Map<UUID, String> branchNames, Map<UUID, String> productNames) {
        return new MovementReportRow(
                movement.getCreatedAt(),
                movement.getBranchId(),
                nameOrId(branchNames, movement.getBranchId()),
                movement.getProductId(),
                nameOrId(productNames, movement.getProductId()),
                movement.getType(),
                movement.getQuantity(),
                movement.getReason());
    }

    private static PaymentReportRow toPaymentRow(Payment payment) {
        String reference = payment.getReference() != null && !payment.getReference().isBlank()
                ? payment.getReference()
                : NO_VALUE;
        String origin = payment.getOrderId() != null ? "Orden" : payment.getSaleId() != null ? "Venta" : NO_VALUE;
        return new PaymentReportRow(
                payment.getCreatedAt(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getAmount(),
                reference,
                origin);
    }

    // Igual que el frontend: si el nombre no se encuentra, se muestra el id.
    private static String nameOrId(Map<UUID, String> names, UUID id) {
        if (id == null) {
            return NO_VALUE;
        }
        return names.getOrDefault(id, id.toString());
    }
}
