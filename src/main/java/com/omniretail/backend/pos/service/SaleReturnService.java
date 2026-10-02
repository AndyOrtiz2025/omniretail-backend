package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.service.KitFulfillmentSnapshot;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.SaleReturnResponse;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleReturn;
import com.omniretail.backend.pos.entity.SaleReturnItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.pos.repository.SaleReturnItemRepository;
import com.omniretail.backend.pos.repository.SaleReturnRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SaleReturnService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard capability;
    private final BranchAccessResolver branches;
    private final SaleRepository sales;
    private final SaleItemRepository saleItems;
    private final SaleReturnRepository returns;
    private final SaleReturnItemRepository returnItems;
    private final ProductRepository products;
    private final InventoryStockService inventory;
    private final CashShiftRepository shifts;
    private final CashMovementRepository movements;
    private final PaymentRepository payments;

    public SaleReturnResponse create(UUID saleId, CreateSaleReturnRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);

        Sale sale = sales.findByTenantIdAndIdForUpdate(actor.tenantId(), saleId)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branches.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        if (sale.getStatus() != SaleStatus.completed
                && sale.getStatus() != SaleStatus.partially_returned) {
            throw new BusinessException(HttpStatus.CONFLICT, "SALE_NOT_RETURNABLE",
                    "La venta no admite devoluciones.");
        }

        Map<UUID, SaleItem> available = saleItems.findByTenantIdAndSaleId(actor.tenantId(), saleId)
                .stream().collect(Collectors.toMap(SaleItem::getId, item -> item));
        Set<UUID> seen = new HashSet<>();
        List<SaleReturnItem> created = new ArrayList<>();
        Map<UUID, BigDecimal> returnedQuantities = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;

        SaleReturn saleReturn = SaleReturn.builder()
                .branchId(sale.getBranchId()).saleId(saleId).reason(request.reason())
                .createdByUserId(actor.userId()).refundAmount(BigDecimal.ZERO).build();
        saleReturn.setTenantId(actor.tenantId());
        saleReturn = returns.save(saleReturn);

        for (CreateSaleReturnRequest.Line line : request.lines()) {
            if (!seen.add(line.saleItemId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_RETURN_ITEM",
                        "La línea está repetida.");
            }
            SaleItem item = available.get(line.saleItemId());
            if (item == null) {
                throw notFound("SALE_ITEM_NOT_FOUND", "Línea no encontrada.");
            }
            BigDecimal previous = Optional.ofNullable(returnItems.sumReturned(actor.tenantId(), item.getId()))
                    .orElse(BigDecimal.ZERO);
            if (line.quantity().compareTo(item.getQuantity().subtract(previous)) > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "RETURN_QUANTITY_EXCEEDED",
                        "La cantidad supera la disponible para devolución.");
            }
            BigDecimal unitNet = item.getUnitPrice().subtract(
                    item.getDiscount().divide(item.getQuantity(), 8, RoundingMode.HALF_UP));
            BigDecimal refund = unitNet.multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP);
            total = total.add(refund);
            returnedQuantities.put(item.getId(), previous.add(line.quantity()));

            SaleReturnItem returnItem = SaleReturnItem.builder()
                    .returnId(saleReturn.getId()).saleItemId(item.getId()).productId(item.getProductId())
                    .quantity(line.quantity()).refundAmount(refund).build();
            returnItem.setTenantId(actor.tenantId());
            created.add(returnItems.save(returnItem));

            List<KitFulfillmentSnapshot.Component> fulfillment = KitFulfillmentSnapshot.decode(item.getFulfillmentComponents());
            if (!fulfillment.isEmpty()) {
                for (KitFulfillmentSnapshot.Component component : fulfillment) {
                    inventory.incrementStock(new AddStockCommand(actor.tenantId(), sale.getBranchId(),
                            component.productId(), line.quantity().multiply(component.quantityPerKit()),
                            "Devolucion venta kit POS #" + sale.getNumber(), "POS_KIT_SALE_RETURN",
                            saleReturn.getId(), actor.userId()));
                }
            } else {
                Product product = products.findByTenantIdAndId(actor.tenantId(), item.getProductId()).orElse(null);
                if (product != null && Boolean.TRUE.equals(product.getTrackingStock())
                        && product.getProductType() == ProductType.physical) {
                    inventory.incrementStock(new AddStockCommand(actor.tenantId(), sale.getBranchId(),
                            product.getId(), line.quantity(), "Devolución venta POS #" + sale.getNumber(),
                            "POS_SALE_RETURN", saleReturn.getId(), actor.userId()));
                }
            }
        }

        saleReturn.setRefundAmount(total.setScale(2, RoundingMode.HALF_UP));
        returns.save(saleReturn);
        registerCashRefund(actor, sale, saleReturn, total);

        boolean allReturned = available.values().stream().allMatch(item -> item.getQuantity().compareTo(
                returnedQuantities.containsKey(item.getId()) ? returnedQuantities.get(item.getId())
                        : Optional.ofNullable(returnItems.sumReturned(actor.tenantId(), item.getId()))
                                .orElse(BigDecimal.ZERO)) <= 0);
        sale.setStatus(allReturned ? SaleStatus.returned : SaleStatus.partially_returned);
        sales.save(sale);
        return SaleReturnResponse.from(saleReturn, created);
    }

    private void registerCashRefund(AuthenticatedUser actor, Sale sale, SaleReturn saleReturn,
            BigDecimal total) {
        BigDecimal cash = payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), sale.getId())
                .stream().filter(payment -> payment.getMethod() == PaymentMethod.cash)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashRefund = sale.getTotal().signum() == 0 ? BigDecimal.ZERO
                : total.multiply(cash).divide(sale.getTotal(), 2, RoundingMode.HALF_UP);
        if (cash.signum() <= 0 || cashRefund.signum() <= 0) {
            return;
        }
        Optional<CashShift> currentShift = shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(
                actor.tenantId(), sale.getBranchId(), actor.userId(), CashShiftStatus.open);
        if (currentShift.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_OPEN_CASH_SHIFT",
                    "Se requiere un turno de caja abierto para registrar el egreso de efectivo.");
        }
        movements.save(CashMovement.builder().tenantId(actor.tenantId())
                .cashShiftId(currentShift.get().getId()).type(CashMovementType.out).amount(cashRefund)
                .reason("Devolución venta POS #" + sale.getNumber()).referenceType("sale_return")
                .referenceId(saleReturn.getId()).createdByUserId(actor.userId()).build());
    }

    @Transactional(readOnly = true)
    public Page<SaleReturnResponse> list(UUID branchId, Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        if (!branches.resolve(actor).allows(branchId)) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        return returns.findByTenantIdAndBranchId(actor.tenantId(), branchId, pageable)
                .map(value -> SaleReturnResponse.from(value,
                        returnItems.findByTenantIdAndReturnId(actor.tenantId(), value.getId())));
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
