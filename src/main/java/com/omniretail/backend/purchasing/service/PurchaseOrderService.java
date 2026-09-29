package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.purchasing.dto.*;
import com.omniretail.backend.purchasing.entity.*;
import com.omniretail.backend.purchasing.repository.*;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.*;
import java.math.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional @RequiredArgsConstructor
public class PurchaseOrderService {
    private final CurrentUser currentUser; private final TenantCapabilityGuard capability;
    private final SupplierRepository suppliers; private final BranchRepository branches;
    private final ProductRepository products; private final SupplierProductRepository supplierProducts;
    private final TenantRepository tenants; private final DocumentCounterService counter;
    private final PurchaseOrderRepository orders; private final PurchaseOrderItemRepository items;

    public PurchaseOrderResponse create(CreatePurchaseOrderRequest request) {
        AuthenticatedUser actor = currentUser.require(); capability.ensureTenantCapability(actor.tenantId(), SaasCapability.purchasing);
        suppliers.findByTenantIdAndId(actor.tenantId(), request.supplierId()).filter(s -> s.getStatus() == SupplierStatus.active).orElseThrow(() -> missing("SUPPLIER_NOT_FOUND"));
        var tenant = tenants.findById(actor.tenantId()).orElseThrow(() -> missing("TENANT_NOT_FOUND"));
        branches.findByTenantIdAndId(actor.tenantId(), request.branchId()).orElseThrow(() -> missing("BRANCH_NOT_FOUND"));
        BigDecimal total = BigDecimal.ZERO;
        List<PurchaseOrderItem> lines = new ArrayList<>();
        for (var line : request.items()) {
            var product = products.findByTenantIdAndId(actor.tenantId(), line.productId()).filter(p -> p.getStatus() == ProductStatus.published)
                    .orElseThrow(() -> missing("PRODUCT_NOT_FOUND"));
            SupplierProduct supplierProduct = supplierProducts.findByTenantIdAndSupplierIdAndProductIdAndActiveTrue(actor.tenantId(), request.supplierId(), line.productId()).orElseThrow(() -> missing("SUPPLIER_PRODUCT_NOT_FOUND"));
            BigDecimal subtotal = line.quantity().multiply(line.unitCost()).setScale(2, RoundingMode.HALF_UP);
            total = total.add(subtotal);
            lines.add(PurchaseOrderItem.builder().productId(line.productId()).skuSnapshot(product.getSku()).nameSnapshot(product.getName()).supplierSkuSnapshot(supplierProduct.getSupplierSku()).purchaseUnitId(supplierProduct.getPurchaseUnitId()).purchaseToBaseFactor(supplierProduct.getPurchaseToBaseFactor()).quantity(line.quantity()).unitCost(line.unitCost().setScale(2, RoundingMode.HALF_UP)).subtotal(subtotal).build());
        }
        PurchaseOrder order = PurchaseOrder.builder().supplierId(request.supplierId()).branchId(request.branchId())
                .number(counter.nextPurchaseOrderNumber(actor.tenantId())).status(PurchaseOrderStatus.draft).currency(tenant.getDefaultCurrency())
                .totalAmount(total.setScale(2, RoundingMode.HALF_UP)).createdByUserId(actor.userId()).build();
        order.setTenantId(actor.tenantId()); order = orders.saveAndFlush(order);
        for (var line : lines) { line.setPurchaseOrderId(order.getId()); items.save(line); }
        return PurchaseOrderResponse.from(order);
    }
    @Transactional(readOnly = true)
    public List<PurchaseOrderResponse> list() { AuthenticatedUser actor=currentUser.require(); capability.ensureTenantCapability(actor.tenantId(), SaasCapability.purchasing);
        return orders.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream().map(PurchaseOrderResponse::from).toList(); }
    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(UUID id) { AuthenticatedUser actor = currentUser.require(); capability.ensureTenantCapability(actor.tenantId(), SaasCapability.purchasing); return PurchaseOrderResponse.from(orders.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> missing("PURCHASE_ORDER_NOT_FOUND"))); }
    public PurchaseOrderResponse submit(UUID id) { return changeStatus(id, PurchaseOrderStatus.submitted); }
    public PurchaseOrderResponse approve(UUID id) { return changeStatus(id, PurchaseOrderStatus.approved); }
    private PurchaseOrderResponse changeStatus(UUID id, PurchaseOrderStatus status) { AuthenticatedUser actor=currentUser.require(); capability.ensureTenantCapability(actor.tenantId(), SaasCapability.purchasing);
        PurchaseOrder order=orders.findByTenantIdAndId(actor.tenantId(),id).orElseThrow(()->missing("PURCHASE_ORDER_NOT_FOUND"));
        if (status == PurchaseOrderStatus.submitted && order.getStatus()!=PurchaseOrderStatus.draft) throw new BusinessException(HttpStatus.CONFLICT,"PURCHASE_ORDER_STATUS_CONFLICT","La orden no está en borrador.");
        if (status == PurchaseOrderStatus.approved && order.getStatus()!=PurchaseOrderStatus.submitted) throw new BusinessException(HttpStatus.CONFLICT,"PURCHASE_ORDER_STATUS_CONFLICT","La orden debe estar enviada.");
        order.setStatus(status); return PurchaseOrderResponse.from(orders.save(order)); }
    private BusinessException missing(String code) { return new BusinessException(HttpStatus.NOT_FOUND, code, "Registro no encontrado."); }
}
