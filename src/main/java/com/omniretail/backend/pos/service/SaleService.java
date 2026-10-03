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
import com.omniretail.backend.catalog.service.ProductKitService;
import com.omniretail.backend.catalog.service.KitFulfillmentSnapshot;
import com.omniretail.backend.catalog.service.ProductUnitConversionResolver;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.CashMovementResponse;
import com.omniretail.backend.pos.dto.SaleConfirmationResponse;
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
import com.omniretail.backend.pos.entity.SaleDocumentType;
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
    private final InventoryMovementRepository inventoryMovements;
    private final DocumentCounterService counter;
    private final ProductPriceResolver productPriceResolver;
    private final ProductKitService productKitService;
    private final ProductUnitConversionResolver unitConversionResolver;

    public SaleConfirmationResponse create(CreateSaleRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (request.confirmationId() == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "CONFIRMATION_ID_REQUIRED",
                    "La confirmación de la venta es obligatoria.");
        }
        NormalizedDocument document = normalizeDocument(request.document());
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
            String currentFingerprint = fingerprint(request, document);
            if (existing.get().getConfirmationFingerprint() != null
                    && !existing.get().getConfirmationFingerprint().equals(currentFingerprint)
                    && !matchesLegacyConfirmation(existing.get(), request, document)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "La confirmación ya fue usada con una venta distinta.");
            }
            return confirmationResponse(actor.tenantId(), existing.get(), true);
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
            ResolvedProductPrice resolved = productPriceResolver.resolveEffectivePrice(
                    actor.tenantId(), product, pricingAt, "pos", request.branchId(), line.quantity());
            BigDecimal gross = money(resolved.basePrice().multiply(line.quantity()));
            BigDecimal manualDiscount = money(line.discount() == null ? BigDecimal.ZERO : line.discount());
            if (manualDiscount.compareTo(gross) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT", "El descuento supera el importe de la línea.");
            }
            BigDecimal promotionDiscount = money(resolved.discountAmount().multiply(line.quantity()));
            boolean usePromotion = resolved.promotionId() != null
                    && promotionDiscount.compareTo(manualDiscount) >= 0;
            BigDecimal lineDiscount = usePromotion ? promotionDiscount : manualDiscount;
            if (lineDiscount.compareTo(gross) > 0) {
                lineDiscount = gross;
            }
            BigDecimal lineSubtotal = money(gross.subtract(lineDiscount));
            BigDecimal inventoryQuantity = product.getProductType() == ProductType.physical
                            && Boolean.TRUE.equals(product.getTrackingStock())
                    ? unitConversionResolver.toBaseQuantity(actor.tenantId(), product, line.quantity())
                    : null;
            catalog.add(new LinePricing(product, resolved.basePrice(), inventoryQuantity,
                    lineDiscount, lineSubtotal, usePromotion ? resolved.promotionId() : null));
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
                .confirmationFingerprint(fingerprint(request, document))
                .documentType(document.type()).documentTaxId(document.taxId())
                .documentLegalName(document.legalName()).documentFiscalAddress(document.fiscalAddress())
                .subtotal(subtotal)
                .discountTotal(discount).taxTotal(tax).total(total).build();
        sale.setTenantId(actor.tenantId());
        sale = sales.saveAndFlush(sale);
        List<SaleItem> savedItems = new ArrayList<>();
        List<InventoryMovement> createdInventoryMovements = new ArrayList<>();
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            LinePricing pricing = catalog.get(index);
            Product product = pricing.product();
            List<ProductKitService.FulfillmentComponent> fulfillment = product.getProductType() == ProductType.kit
                    ? productKitService.fulfillment(actor.tenantId(), product, line.quantity()) : List.of();
            if (!fulfillment.isEmpty()) {
                for (ProductKitService.FulfillmentComponent component : fulfillment) {
                    createdInventoryMovements.add(inventory.deductStock(new DeductStockCommand(
                            actor.tenantId(), request.branchId(), component.productId(), component.quantity(),
                            "Venta kit POS #" + sale.getNumber(), "POS_KIT_SALE", sale.getId(), actor.userId())));
                }
            } else if (Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType() == ProductType.physical) {
                createdInventoryMovements.add(inventory.deductStock(new DeductStockCommand(
                        actor.tenantId(), request.branchId(), product.getId(), pricing.inventoryQuantity(),
                        "Venta POS #" + sale.getNumber(), "POS_SALE", sale.getId(), actor.userId())));
            }
            SaleItem saleItem = SaleItem.builder().saleId(sale.getId()).productId(product.getId())
                    .skuSnapshot(product.getSku()).nameSnapshot(product.getName())
                    .quantity(line.quantity()).unitPrice(pricing.unitPrice())
                    .discount(pricing.discount()).subtotal(pricing.subtotal())
                    .promotionId(pricing.promotionId())
                    .fulfillmentComponents(KitFulfillmentSnapshot.encode(fulfillment)).build();
            items.save(saleItem);
            savedItems.add(saleItem);
        }
        List<Payment> savedPayments = new ArrayList<>();
        BigDecimal cashAmount = BigDecimal.ZERO.setScale(2);
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
            savedPayments.add(payment);
            if (paymentRequest.method() == PaymentMethod.cash) {
                cashAmount = cashAmount.add(paymentRequest.amount()).setScale(2, RoundingMode.HALF_UP);
            }
        }
        CashMovement cashMovement = null;
        if (cashAmount.signum() > 0) {
            cashMovement = CashMovement.builder().tenantId(actor.tenantId()).cashShiftId(shift.getId())
                    .type(CashMovementType.in).amount(cashAmount)
                    .reason("Venta POS #" + sale.getNumber()).referenceType("sale").referenceId(sale.getId())
                    .createdByUserId(actor.userId()).build();
            cashMovements.save(cashMovement);
        }
        return confirmationResponse(
                sale, savedItems, savedPayments, createdInventoryMovements, cashMovement, false);
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
        if (sale.getStatus() != SaleStatus.completed) {
            throw new BusinessException(HttpStatus.CONFLICT, "SALE_NOT_VOIDABLE",
                    "Una venta con devoluciones no puede anularse.");
        }
        List<InventoryMovement> originalMovements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        actor.tenantId(), List.of("POS_SALE", "POS_KIT_SALE"), sale.getId());
        for (SaleItem item : items.findByTenantIdAndSaleId(actor.tenantId(), id)) {
            Product product = products.findByTenantIdAndId(actor.tenantId(), item.getProductId()).orElse(null);
            List<KitFulfillmentSnapshot.Component> fulfillment = KitFulfillmentSnapshot.decode(item.getFulfillmentComponents());
            if (!fulfillment.isEmpty()) {
                for (KitFulfillmentSnapshot.Component component : fulfillment) {
                    inventory.incrementStock(new AddStockCommand(actor.tenantId(), sale.getBranchId(), component.productId(),
                            item.getQuantity().multiply(component.quantityPerKit()), "Anulacion venta kit POS #" + sale.getNumber(),
                            "POS_KIT_SALE_VOID", sale.getId(), actor.userId()));
                }
            } else if (product != null && Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType() == ProductType.physical) {
                BigDecimal physicalQuantity = originalMovements.stream()
                        .filter(movement -> "POS_SALE".equals(movement.getReferenceType()))
                        .filter(movement -> product.getId().equals(movement.getProductId()))
                        .map(InventoryMovement::getQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (physicalQuantity.signum() == 0) {
                    physicalQuantity = item.getQuantity();
                }
                inventory.incrementStock(new AddStockCommand(actor.tenantId(), sale.getBranchId(), product.getId(), physicalQuantity,
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

    private SaleConfirmationResponse confirmationResponse(UUID tenantId, Sale sale, boolean idempotent) {
        List<SaleItem> saleItems = items.findByTenantIdAndSaleId(tenantId, sale.getId());
        List<Payment> salePayments = payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(
                tenantId, sale.getId());
        List<InventoryMovement> movements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        tenantId, List.of("POS_SALE", "POS_KIT_SALE"), sale.getId());
        CashMovement cashMovement = cashMovements
                .findFirstByTenantIdAndReferenceTypeAndReferenceIdOrderByCreatedAtAscIdAsc(
                        tenantId, "sale", sale.getId())
                .orElse(null);
        return confirmationResponse(sale, saleItems, salePayments, movements, cashMovement, idempotent);
    }

    private static SaleConfirmationResponse confirmationResponse(
            Sale sale,
            List<SaleItem> saleItems,
            List<Payment> salePayments,
            List<InventoryMovement> movements,
            CashMovement cashMovement,
            boolean idempotent) {
        return SaleConfirmationResponse.from(
                SaleResponse.from(sale),
                saleItems.stream().map(SaleItemResponse::from).toList(),
                salePayments.stream().map(PaymentResponse::from).toList(),
                movements.stream().filter(java.util.Objects::nonNull)
                        .map(InventoryMovementResponse::from).toList(),
                cashMovement == null ? null : CashMovementResponse.from(cashMovement),
                idempotent);
    }

    private static NormalizedDocument normalizeDocument(CreateSaleRequest.Document input) {
        SaleDocumentType type = input == null ? SaleDocumentType.ticket : input.type();
        if (type == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "SALE_DOCUMENT_TYPE_REQUIRED",
                    "El tipo de documento es requerido.");
        }
        String taxId = trimToNull(input == null ? null : input.taxId());
        String legalName = trimToNull(input == null ? null : input.legalName());
        String fiscalAddress = trimToNull(input == null ? null : input.fiscalAddress());
        if (type == SaleDocumentType.invoice
                && (taxId == null || legalName == null || fiscalAddress == null)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVOICE_FISCAL_DATA_REQUIRED",
                    "La factura requiere NIT, nombre legal y dirección fiscal.");
        }
        if (type == SaleDocumentType.ticket
                && (taxId != null || legalName != null || fiscalAddress != null)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TICKET_FISCAL_DATA_NOT_ALLOWED",
                    "El ticket no admite datos fiscales de factura.");
        }
        return new NormalizedDocument(type, taxId, legalName, fiscalAddress);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fingerprint(CreateSaleRequest request, NormalizedDocument document) {
        String payload = request.branchId()
                + "|" + request.cashShiftId()
                + "|" + request.customerId()
                + "|" + document.type()
                + ":" + document.taxId()
                + ":" + document.legalName()
                + ":" + document.fiscalAddress()
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
        return sha256(payload);
    }

    private static boolean matchesLegacyConfirmation(
            Sale sale, CreateSaleRequest request, NormalizedDocument document) {
        return sale.getDocumentType() == null
                && document.type() == SaleDocumentType.ticket
                && java.util.Objects.equals(sale.getCashShiftId(), request.cashShiftId())
                && java.util.Objects.equals(sale.getCustomerId(), request.customerId())
                && legacyFingerprint(request).equals(sale.getConfirmationFingerprint());
    }

    private static String legacyFingerprint(CreateSaleRequest request) {
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
        return sha256(payload);
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    @SuppressWarnings("unused")
    private static String fingerprint(CreateSaleRequest request) {
        return fingerprint(request, normalizeDocument(request.document()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record LinePricing(
            Product product,
            BigDecimal unitPrice,
            BigDecimal inventoryQuantity,
            BigDecimal discount,
            BigDecimal subtotal,
            UUID promotionId) {}

    private record NormalizedDocument(
            SaleDocumentType type,
            String taxId,
            String legalName,
            String fiscalAddress) {}
}
