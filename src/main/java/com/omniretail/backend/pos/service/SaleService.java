package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional @RequiredArgsConstructor
public class SaleService {
    private final CurrentUser currentUser; private final TenantCapabilityGuard capability;
    private final BranchAccessResolver branchAccess; private final CashShiftRepository shifts;
    private final ProductRepository products; private final SaleRepository sales;
    private final SaleItemRepository items; private final PaymentRepository payments;
    private final InventoryStockService inventory; private final DocumentCounterService counter;

    public SaleResponse create(CreateSaleRequest request) {
        AuthenticatedUser actor = currentUser.require(); capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (!branchAccess.resolve(actor).allows(request.branchId())) throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        var shift = shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(actor.tenantId(), request.branchId(), actor.userId(), CashShiftStatus.open)
                .filter(s -> s.getId().equals(request.cashShiftId())).orElseThrow(() -> notFound("CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        List<Product> catalog = new ArrayList<>(); BigDecimal subtotal = BigDecimal.ZERO, discount = BigDecimal.ZERO;
        for (var line : request.items()) {
            Product product = products.findByTenantIdAndId(actor.tenantId(), line.productId())
                    .filter(p -> p.getStatus() == ProductStatus.published && Boolean.TRUE.equals(p.getChannelPos()))
                    .orElseThrow(() -> notFound("PRODUCT_NOT_FOUND", "Producto no encontrado o no disponible para POS."));
            catalog.add(product); BigDecimal d = line.discount() == null ? BigDecimal.ZERO : line.discount();
            BigDecimal lineSubtotal = product.getSalePrice().multiply(line.quantity()).subtract(d);
            if (lineSubtotal.signum() < 0) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT", "El descuento supera el importe de la línea.");
            subtotal = subtotal.add(lineSubtotal); discount = discount.add(d);
        }
        BigDecimal tax = request.taxTotal() == null ? BigDecimal.ZERO : request.taxTotal(); BigDecimal total = subtotal.add(tax);
        BigDecimal paid = request.payments().stream().map(CreateSaleRequest.PaymentLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (paid.compareTo(total) != 0) throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_TOTAL_MISMATCH", "Los pagos deben coincidir con el total.");
        Sale sale = Sale.builder().branchId(request.branchId()).cashShiftId(shift.getId()).createdByUserId(actor.userId())
                .number(counter.nextPosSaleNumber(actor.tenantId())).customerId(request.customerId()).subtotal(subtotal).discountTotal(discount).taxTotal(tax).total(total).build();
        sale.setTenantId(actor.tenantId());
        sale = sales.saveAndFlush(sale);
        for (int i=0;i<request.items().size();i++) { var line=request.items().get(i); Product p=catalog.get(i); BigDecimal d=line.discount()==null?BigDecimal.ZERO:line.discount();
            inventory.deductStock(new DeductStockCommand(actor.tenantId(), request.branchId(), p.getId(), line.quantity(), "Venta POS", "POS_SALE", sale.getId(), actor.userId()));
            items.save(SaleItem.builder().saleId(sale.getId()).productId(p.getId()).skuSnapshot(p.getSku()).nameSnapshot(p.getName()).quantity(line.quantity()).unitPrice(p.getSalePrice()).discount(d).subtotal(p.getSalePrice().multiply(line.quantity()).subtract(d)).build()); }
        for (var p : request.payments()) {
            Payment payment = Payment.builder().saleId(sale.getId()).method(p.method()).status(PaymentStatus.approved)
                    .amount(p.amount()).currency("GTQ").reference(p.reference()).build();
            payment.setTenantId(actor.tenantId());
            payments.save(payment);
        }
        return SaleResponse.from(sale);
    }
    private static BusinessException notFound(String code, String message) { return new BusinessException(HttpStatus.NOT_FOUND, code, message); }
}
