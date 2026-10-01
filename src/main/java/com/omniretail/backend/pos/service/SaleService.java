package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.repository.BusinessCapabilitiesConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
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
import com.omniretail.backend.pos.entity.SaleStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.stream.Collectors;
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
    private final CustomerRepository customers;
    private final BusinessCapabilitiesConfigRepository businessConfig;
    private final BankAccountRepository bankAccounts;
    private final SaleRepository sales;
    private final SaleItemRepository items;
    private final PaymentRepository payments;
    private final CashMovementRepository cashMovements;
    private final InventoryStockService inventory;
    private final DocumentCounterService counter;
    private final ProductPriceResolver productPriceResolver;

    public SaleResponse create(CreateSaleRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (request.confirmationId() == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "CONFIRMATION_ID_REQUIRED",
                    "La confirmación de la venta es obligatoria.");
        }
        if (!branchAccess.resolve(actor).allows(request.branchId())) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        if (request.customerId() != null
                && customers.findByTenantIdAndId(actor.tenantId(), request.customerId()).isEmpty()) {
            throw notFound("CUSTOMER_NOT_FOUND", "Cliente no encontrado.");
        }
        var shift = shifts.findOwnedByIdForUpdate(
                        actor.tenantId(), actor.userId(), request.cashShiftId())
                .filter(found -> found.getBranchId().equals(request.branchId())
                        && found.getStatus() == CashShiftStatus.open)
                .orElseThrow(() -> notFound("CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        var existing = sales.findByTenantIdAndConfirmationId(actor.tenantId(), request.confirmationId());
        if (existing.isPresent()) {
            String currentFingerprint = fingerprint(request);
            if (existing.get().getConfirmationFingerprint() != null
                    && !existing.get().getConfirmationFingerprint().equals(currentFingerprint)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "La confirmación ya fue usada con una venta distinta.");
            }
            return SaleResponse.from(existing.get());
        }
        Tenant tenant = tenants.findById(actor.tenantId())
                .orElseThrow(() -> notFound("TENANT_NOT_FOUND", "Negocio no encontrado."));
        Set<UUID> productIds = new HashSet<>();
        Instant pricingAt = Instant.now();
        List<LinePricing> catalog = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (var line : request.items()) {
            if (!productIds.add(line.productId())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "DUPLICATE_PRODUCT",
                        "No se permiten productos repetidos en una venta.");
            }
            Product product = products.findByTenantIdAndId(actor.tenantId(), line.productId())
                    .filter(found -> found.getStatus() == ProductStatus.published
                            && Boolean.TRUE.equals(found.getChannelPos()))
                    .orElseThrow(() -> notFound("PRODUCT_NOT_FOUND", "Producto no encontrado o no disponible para POS."));
            BigDecimal gross = money(product.getSalePrice().multiply(line.quantity()));
            BigDecimal manualDiscount = money(line.discount() == null ? BigDecimal.ZERO : line.discount());
            if (manualDiscount.compareTo(gross) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT", "El descuento supera el importe de la línea.");
            }
            ResolvedProductPrice resolved = productPriceResolver.resolveEffectivePrice(
                    actor.tenantId(), product, pricingAt);
            BigDecimal promotionDiscount = money(resolved.discountAmount().multiply(line.quantity()));
            boolean usePromotion = resolved.promotionId() != null
                    && promotionDiscount.compareTo(manualDiscount) >= 0;
            BigDecimal lineDiscount = usePromotion ? promotionDiscount : manualDiscount;
            if (lineDiscount.compareTo(gross) > 0) {
                lineDiscount = gross;
            }
            BigDecimal lineSubtotal = money(gross.subtract(lineDiscount));
            catalog.add(new LinePricing(
                    product, lineDiscount, lineSubtotal, usePromotion ? resolved.promotionId() : null));
            subtotal = subtotal.add(lineSubtotal).setScale(2, RoundingMode.HALF_UP);
            discount = discount.add(lineDiscount).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        validatePayments(actor, request);
        BigDecimal paid = request.payments().stream().map(CreateSaleRequest.PaymentLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(total) != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_TOTAL_MISMATCH", "Los pagos deben coincidir con el total.");
        }
        Sale sale = Sale.builder().branchId(request.branchId()).cashShiftId(shift.getId())
                .createdByUserId(actor.userId()).number(counter.nextPosSaleNumber(actor.tenantId()))
                .customerId(request.customerId()).confirmationId(request.confirmationId())
                .confirmationFingerprint(fingerprint(request)).subtotal(subtotal)
                .discountTotal(discount).taxTotal(tax).total(total).build();
        sale.setTenantId(actor.tenantId());
        sale = sales.saveAndFlush(sale);
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            LinePricing pricing = catalog.get(index);
            Product product = pricing.product();
            if (Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType() == ProductType.physical) {
                inventory.deductStock(new DeductStockCommand(actor.tenantId(), request.branchId(), product.getId(),
                        line.quantity(), "Venta POS #" + sale.getNumber(), "POS_SALE", sale.getId(), actor.userId()));
            }
            items.save(SaleItem.builder().saleId(sale.getId()).productId(product.getId()).skuSnapshot(product.getSku())
                    .nameSnapshot(product.getName()).quantity(line.quantity()).unitPrice(product.getSalePrice())
                    .discount(pricing.discount()).subtotal(pricing.subtotal())
                    .promotionId(pricing.promotionId()).build());
        }
        for (var paymentRequest : request.payments()) {
            UUID bankAccountId = paymentRequest.method() == PaymentMethod.transfer
                    ? paymentRequest.bankAccountId()
                    : null;
            Boolean externallyVerified = paymentRequest.method() == PaymentMethod.transfer
                    ? paymentRequest.externallyVerified()
                    : null;
            Payment payment = Payment.builder().saleId(sale.getId()).method(paymentRequest.method())
                    .status(PaymentStatus.approved).amount(paymentRequest.amount().setScale(2, RoundingMode.HALF_UP))
                    .currency(tenant.getDefaultCurrency()).bankAccountId(bankAccountId)
                    .reference(paymentRequest.reference()).externallyVerified(externallyVerified)
                    .verifiedByUserId(externallyVerified == null ? null : actor.userId())
                    .verifiedAt(externallyVerified == null ? null : Instant.now())
                    .build();
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
        if ((from == null) != (to == null) || (from != null && from.isAfter(to))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SALE_DATE_RANGE",
                    "Las fechas from y to son requeridas juntas y deben formar un rango válido.");
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

    private void validatePayments(AuthenticatedUser actor, CreateSaleRequest request) {
        var config = businessConfig.findByTenantId(actor.tenantId()).orElse(null);
        for (CreateSaleRequest.PaymentLine payment : request.payments()) {
            if (payment.amount().signum() <= 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_AMOUNT_INVALID",
                        "El monto del pago debe ser mayor que cero.");
            }
            if (config != null && config.getAllowedPosPaymentMethods() != null
                    && !config.getAllowedPosPaymentMethods().isEmpty()
                    && !config.getAllowedPosPaymentMethods().contains(payment.method().name())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_METHOD_NOT_ALLOWED",
                        "Método de pago no permitido.");
            }
            if (payment.method() == PaymentMethod.card
                    && (payment.reference() == null || payment.reference().isBlank())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_REFERENCE_REQUIRED",
                        "La tarjeta requiere referencia.");
            }
            if (payment.method() == PaymentMethod.transfer) {
                if (payment.bankAccountId() == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "BANK_ACCOUNT_INVALID",
                            "La cuenta bancaria no es válida para la sucursal.");
                }
                if (payment.reference() == null || payment.reference().isBlank()) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "PAYMENT_REFERENCE_REQUIRED",
                            "La transferencia requiere el número de comprobante.");
                }
                if (payment.externallyVerified() == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "EXTERNAL_VERIFICATION_REQUIRED",
                            "La transferencia requiere indicar su verificación externa.");
                }
                boolean validAccount = bankAccounts.findByTenantIdAndId(actor.tenantId(), payment.bankAccountId())
                        .filter(account -> account.getStatus() == BankAccountStatus.active)
                        .filter(account -> account.getBranchIds().contains(request.branchId()))
                        .isPresent();
                if (!validAccount) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "BANK_ACCOUNT_INVALID",
                            "La cuenta bancaria no es válida para la sucursal.");
                }
            }
        }
    }

    private static String fingerprint(CreateSaleRequest request) {
        String payload = request.branchId()
                + "|" + request.items().stream()
                        .map(item -> item.productId() + ":" + item.quantity().stripTrailingZeros().toPlainString()
                                + ":" + (item.discount() == null ? BigDecimal.ZERO : item.discount())
                                        .stripTrailingZeros().toPlainString())
                        .sorted()
                        .collect(Collectors.joining(","))
                + "|" + request.payments().stream()
                        .map(payment -> payment.method() + ":"
                                + payment.amount().stripTrailingZeros().toPlainString()
                                + ":" + payment.bankAccountId()
                                + ":" + payment.reference()
                                + ":" + payment.externallyVerified())
                        .sorted()
                        .collect(Collectors.joining(","));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record LinePricing(
            Product product, BigDecimal discount, BigDecimal subtotal, UUID promotionId) {}
}
