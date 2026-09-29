package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.BusinessCapabilitiesConfigRepository;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.SaleResponse;
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
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
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
    private final BusinessCapabilitiesConfigRepository businessConfig;
    private final CustomerRepository customers;
    private final BankAccountRepository bankAccounts;
    private final UnitConversionRepository conversions;

    public SaleResponse create(CreateSaleRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (!branchAccess.resolve(actor).allows(request.branchId())) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        var shift = shifts.findOwnedByIdForUpdate(actor.tenantId(), actor.userId(), request.cashShiftId())
                .filter(found -> found.getBranchId().equals(request.branchId()) && found.getStatus() == CashShiftStatus.open)
                .orElseThrow(() -> notFound("CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        if (sales.findByTenantIdAndConfirmationId(actor.tenantId(), request.confirmationId()).isPresent()) {
            return SaleResponse.from(sales.findByTenantIdAndConfirmationId(actor.tenantId(), request.confirmationId()).orElseThrow());
        }
        if (request.customerId() != null && customers.findByTenantIdAndId(actor.tenantId(), request.customerId()).isEmpty()) {
            throw notFound("CUSTOMER_NOT_FOUND", "Cliente no encontrado.");
        }
        Set<java.util.UUID> productIds = new HashSet<>();
        List<CreateSaleRequest.Item> saleLines = request.items().stream()
                .sorted(Comparator.comparing(CreateSaleRequest.Item::productId)).toList();
        Tenant tenant = tenants.findById(actor.tenantId())
                .orElseThrow(() -> notFound("TENANT_NOT_FOUND", "Negocio no encontrado."));
        List<Product> catalog = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (var line : saleLines) {
            if (!productIds.add(line.productId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "No se permiten productos repetidos.");
            }
            Product product = products.findByTenantIdAndId(actor.tenantId(), line.productId())
                    .filter(found -> found.getStatus() == ProductStatus.published
                            && Boolean.TRUE.equals(found.getChannelPos()))
                    .orElseThrow(() -> notFound("PRODUCT_NOT_FOUND", "Producto no encontrado o no disponible para POS."));
            if (Boolean.TRUE.equals(product.getTrackingLot()) || Boolean.TRUE.equals(product.getTrackingSerial())) {
                throw new BusinessException(HttpStatus.CONFLICT, "LOT_SERIAL_NOT_SUPPORTED", "Los productos con lote o serie no pueden venderse por POS.");
            }
            BigDecimal lineDiscount = BigDecimal.ZERO;
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
        BigDecimal tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        var config = businessConfig.findByTenantId(actor.tenantId()).orElse(null);
        for (var payment : request.payments()) {
            if (config != null && config.getAllowedPosPaymentMethods() != null
                    && !config.getAllowedPosPaymentMethods().isEmpty()
                    && !config.getAllowedPosPaymentMethods().contains(payment.method().name())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_NOT_ALLOWED", "Método de pago no permitido.");
            }
            if (payment.method() == PaymentMethod.card && (payment.reference() == null || payment.reference().isBlank())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_REFERENCE_REQUIRED", "La tarjeta requiere referencia.");
            }
            if (payment.method() == PaymentMethod.transfer) {
                try {
                    var account = bankAccounts.findByTenantIdAndId(actor.tenantId(), java.util.UUID.fromString(payment.reference()))
                            .filter(a -> a.getStatus() == BankAccountStatus.active && a.getBranchIds().contains(request.branchId()))
                            .orElseThrow();
                } catch (Exception ex) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "BANK_ACCOUNT_INVALID", "La cuenta bancaria no es válida para la sucursal.");
                }
            }
        }
        BigDecimal paid = request.payments().stream().map(CreateSaleRequest.PaymentLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(total) != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_TOTAL_MISMATCH", "Los pagos deben coincidir con el total.");
        }
        Sale sale = Sale.builder().branchId(request.branchId()).cashShiftId(shift.getId())
                .createdByUserId(actor.userId()).number(counter.nextPosSaleNumber(actor.tenantId()))
                .customerId(request.customerId()).confirmationId(request.confirmationId())
                .confirmationFingerprint(fingerprint(request)).subtotal(subtotal).discountTotal(discount).taxTotal(tax).total(total).build();
        sale.setTenantId(actor.tenantId());
        sale = sales.saveAndFlush(sale);
        for (int index = 0; index < saleLines.size(); index++) {
            var line = saleLines.get(index);
            Product product = catalog.get(index);
            BigDecimal lineDiscount = BigDecimal.ZERO;
            BigDecimal lineSubtotal = product.getSalePrice().multiply(line.quantity())
                    .setScale(2, RoundingMode.HALF_UP).subtract(lineDiscount)
                    .setScale(2, RoundingMode.HALF_UP);
            if (Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType() == ProductType.physical) {
                inventory.deductStock(new DeductStockCommand(actor.tenantId(), request.branchId(), product.getId(),
                        toBaseQuantity(actor.tenantId(), product, line.quantity()), "Venta POS #" + sale.getNumber(), "POS_SALE", sale.getId(), actor.userId()));
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
                        .reason("Venta POS #" + sale.getNumber()).referenceType("sale").referenceId(sale.getId()).saleNumber(sale.getNumber())
                        .createdByUserId(actor.userId()).build();
                cashMovements.save(movement);
            }
        }
        return SaleResponse.from(sale);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private static String fingerprint(CreateSaleRequest request) {
        return Integer.toHexString(request.items().toString().hashCode() * 31 + request.payments().toString().hashCode());
    }

    private BigDecimal toBaseQuantity(java.util.UUID tenantId, Product product, BigDecimal quantity) {
        if (product.getSaleUnitId() == null || product.getSaleUnitId().equals(product.getBaseUnitId())) {
            return quantity;
        }
        return conversions.findByTenantIdAndFromUnitIdAndToUnitIdAndProductIdIsNull(tenantId, product.getSaleUnitId(), product.getBaseUnitId())
                .or(() -> conversions.findByTenantIdAndProductId(tenantId, product.getId()).stream()
                        .filter(c -> c.getFromUnitId().equals(product.getSaleUnitId()) && c.getToUnitId().equals(product.getBaseUnitId())).findFirst())
                .map(c -> quantity.multiply(c.getFactor()).setScale(3, RoundingMode.HALF_UP))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "UNIT_CONVERSION_NOT_FOUND", "No existe conversión a la unidad base."));
    }
}
