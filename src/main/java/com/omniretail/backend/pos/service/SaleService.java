package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.dto.SaleDetailResponse;
import com.omniretail.backend.pos.dto.SaleItemResponse;
import com.omniretail.backend.pos.dto.PaymentResponse;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SaleService {
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard capability;
    private final BranchAccessResolver branchAccess;
    private final CashShiftRepository shifts;
    private final ProductRepository products;
    private final TenantRepository tenants;
    private final SaleRepository sales;
    private final SaleItemRepository items;
    private final PaymentRepository payments;
    private final CashMovementRepository cashMovements;
    private final InventoryStockService inventory;
    private final DocumentCounterService counter;

    public SaleResponse create(CreateSaleRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (!branchAccess.resolve(actor).allows(request.branchId())) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        var shift = shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(
                        actor.tenantId(), request.branchId(), actor.userId(), CashShiftStatus.open)
                .filter(found -> found.getId().equals(request.cashShiftId()))
                .orElseThrow(() -> notFound("CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        Tenant tenant = tenants.findById(actor.tenantId())
                .orElseThrow(() -> notFound("TENANT_NOT_FOUND", "Negocio no encontrado."));
        List<Product> catalog = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (var line : request.items()) {
            Product product = products.findByTenantIdAndId(actor.tenantId(), line.productId())
                    .filter(found -> found.getStatus() == ProductStatus.published
                            && Boolean.TRUE.equals(found.getChannelPos()))
                    .orElseThrow(() -> notFound("PRODUCT_NOT_FOUND", "Producto no encontrado o no disponible para POS."));
            BigDecimal lineDiscount = line.discount() == null ? BigDecimal.ZERO : line.discount();
            BigDecimal lineSubtotal = product.getSalePrice().multiply(line.quantity())
                    .setScale(2, RoundingMode.HALF_UP).subtract(lineDiscount)
                    .setScale(2, RoundingMode.HALF_UP);
            if (lineSubtotal.signum() < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT", "El descuento supera el importe de la línea.");
            }
            catalog.add(product);
            subtotal = subtotal.add(lineSubtotal).setScale(2, RoundingMode.HALF_UP);
            discount = discount.add(lineDiscount).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal tax = request.taxTotal() == null ? BigDecimal.ZERO : request.taxTotal().setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = request.payments().stream().map(CreateSaleRequest.PaymentLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(total) != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_TOTAL_MISMATCH", "Los pagos deben coincidir con el total.");
        }
        Sale sale = Sale.builder().branchId(request.branchId()).cashShiftId(shift.getId())
                .createdByUserId(actor.userId()).number(counter.nextPosSaleNumber(actor.tenantId()))
                .customerId(request.customerId()).subtotal(subtotal).discountTotal(discount).taxTotal(tax).total(total).build();
        sale.setTenantId(actor.tenantId());
        sale = sales.saveAndFlush(sale);
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            Product product = catalog.get(index);
            BigDecimal lineDiscount = line.discount() == null ? BigDecimal.ZERO : line.discount();
            BigDecimal lineSubtotal = product.getSalePrice().multiply(line.quantity())
                    .setScale(2, RoundingMode.HALF_UP).subtract(lineDiscount)
                    .setScale(2, RoundingMode.HALF_UP);
            if (Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType() == ProductType.physical) {
                inventory.deductStock(new DeductStockCommand(actor.tenantId(), request.branchId(), product.getId(),
                        line.quantity(), "Venta POS #" + sale.getNumber(), "POS_SALE", sale.getId(), actor.userId()));
            }
            items.save(SaleItem.builder().saleId(sale.getId()).productId(product.getId()).skuSnapshot(product.getSku())
                    .nameSnapshot(product.getName()).quantity(line.quantity()).unitPrice(product.getSalePrice())
                    .discount(lineDiscount).subtotal(lineSubtotal).build());
        }
        for (var paymentRequest : request.payments()) {
            Payment payment = Payment.builder().saleId(sale.getId()).method(paymentRequest.method())
                    .status(PaymentStatus.approved).amount(paymentRequest.amount().setScale(2, RoundingMode.HALF_UP))
                    .currency(tenant.getDefaultCurrency()).reference(paymentRequest.reference()).build();
            payment.setTenantId(actor.tenantId());
            payments.save(payment);
            if (paymentRequest.method() == PaymentMethod.cash) {
                CashMovement movement = CashMovement.builder().tenantId(actor.tenantId()).cashShiftId(shift.getId()).type(CashMovementType.in)
                        .amount(paymentRequest.amount().setScale(2, RoundingMode.HALF_UP))
                        .reason("Venta POS #" + sale.getNumber()).referenceType("sale").referenceId(sale.getId())
                        .createdByUserId(actor.userId()).build();
                cashMovements.save(movement);
            }
        }
        return SaleResponse.from(sale);
    }

    @Transactional(readOnly = true)
    public Page<SaleResponse> list(UUID branchId, SaleStatus status, Instant from, Instant to, Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (!branchAccess.resolve(actor).allows(branchId)) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        Page<Sale> page;
        if (from != null && to != null) {
            page = status == null
                    ? sales.findByTenantIdAndBranchIdAndCreatedAtBetween(actor.tenantId(), branchId, from, to, pageable)
                    : sales.findByTenantIdAndBranchIdAndStatusAndCreatedAtBetween(actor.tenantId(), branchId, status, from, to, pageable);
        } else {
            page = status == null
                    ? sales.findByTenantIdAndBranchId(actor.tenantId(), branchId, pageable)
                    : sales.findByTenantIdAndBranchIdAndStatus(actor.tenantId(), branchId, status, pageable);
        }
        return page.map(SaleResponse::from);
    }

    @Transactional(readOnly = true)
    public SaleDetailResponse get(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        Sale sale = sales.findByTenantIdAndId(actor.tenantId(), id)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branchAccess.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        return new SaleDetailResponse(SaleResponse.from(sale),
                items.findByTenantIdAndSaleId(actor.tenantId(), id).stream().map(SaleItemResponse::from).toList(),
                payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), id).stream().map(PaymentResponse::from).toList());
    }

    public SaleResponse voidSale(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        Sale sale = sales.findByTenantIdAndIdForUpdate(actor.tenantId(), id)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branchAccess.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        if (sale.getStatus() == SaleStatus.cancelled) {
            throw new BusinessException(HttpStatus.CONFLICT, "SALE_ALREADY_VOIDED", "La venta ya está anulada.");
        }
        for (SaleItem item : items.findByTenantIdAndSaleId(actor.tenantId(), id)) {
            Product product = products.findByTenantIdAndId(actor.tenantId(), item.getProductId()).orElse(null);
            if (product != null && Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType() == ProductType.physical) {
                inventory.incrementStock(new AddStockCommand(actor.tenantId(), sale.getBranchId(), product.getId(), item.getQuantity(),
                        "Anulación venta POS #" + sale.getNumber(), "POS_SALE_VOID", sale.getId(), actor.userId()));
            }
        }
        var shift = shifts.findByTenantIdAndId(actor.tenantId(), sale.getCashShiftId())
                .filter(found -> found.getStatus() == CashShiftStatus.open);
        if (shift.isPresent()) {
            payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), id).stream()
                    .filter(payment -> payment.getMethod() == PaymentMethod.cash)
                    .forEach(payment -> cashMovements.save(CashMovement.builder().tenantId(actor.tenantId())
                            .cashShiftId(sale.getCashShiftId()).type(CashMovementType.out).amount(payment.getAmount())
                            .reason("Anulación venta POS #" + sale.getNumber()).referenceType("sale_void")
                            .referenceId(sale.getId()).createdByUserId(actor.userId()).build()));
        }
        sale.setStatus(SaleStatus.cancelled);
        return SaleResponse.from(sales.save(sale));
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
