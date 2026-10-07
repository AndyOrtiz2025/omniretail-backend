package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.purchasing.repository.PurchaseOrderItemRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderRepository;
import com.omniretail.backend.shared.notification.EmailAttachmentContent;
import com.omniretail.backend.shared.notification.EmailAttachmentReference;
import com.omniretail.backend.shared.notification.EmailAttachmentResolver;
import com.omniretail.backend.shared.notification.EmailDeliveryException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Genera el PDF de una orden de compra aprobada en cada intento de envío del correo (incluidos los reintentos).
 * Una orden aprobada es inmutable en lo comercial (líneas, cantidades, precios, total y notas solo se editan en
 * borrador y las líneas guardan snapshots), así que regenerar por {@code resourceId} produce el mismo contenido
 * comercial; solo los datos descriptivos de proveedor y sucursal se leen vigentes. El estado impreso es siempre
 * "Aprobada": el documento representa la aprobación, no el estado actual de la orden.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderPdfAttachmentResolver implements EmailAttachmentResolver {

    public static final String TYPE = "PURCHASE_ORDER_PDF";
    public static final String MEDIA_TYPE = "application/pdf";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;
    private final PurchaseOrderPdfRenderer renderer;

    @Override
    public boolean supports(String type) {
        return TYPE.equals(type);
    }

    @Override
    public EmailAttachmentContent resolve(UUID tenantId, EmailAttachmentReference reference) {
        // Siempre con tenantId explícito: corre fuera de la request, sin usuario autenticado.
        PurchaseOrder order = purchaseOrderRepository
                .findByTenantIdAndId(tenantId, reference.resourceId())
                .orElseThrow(() -> new EmailDeliveryException(EmailDeliveryException.ATTACHMENT_UNAVAILABLE));
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, order.getId());
        Supplier supplier = supplierRepository.findByTenantIdAndId(tenantId, order.getSupplierId()).orElse(null);
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, order.getBranchId()).orElse(null);
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);

        PurchaseOrderPdfRenderer.Data data = new PurchaseOrderPdfRenderer.Data(
                businessName(tenant),
                order.getNumber(),
                "Aprobada",
                order.getCreatedAt(),
                order.getExpectedDate(),
                order.getNotes(),
                order.getSubtotal(),
                order.getTotal(),
                supplier != null && hasText(supplier.getName()) ? supplier.getName() : order.getSupplierNameSnapshot(),
                supplier == null ? null : supplier.getLegalName(),
                supplier == null ? null : supplier.getTaxId(),
                supplier == null ? null : supplier.getEmail(),
                supplier == null ? null : supplier.getPhone(),
                branch == null ? null : branch.getName(),
                branch == null ? null : branch.getAddress(),
                items.stream()
                        .map(item -> new PurchaseOrderPdfRenderer.Line(
                                item.getProductNameSnapshot(),
                                item.getProductSkuSnapshot(),
                                item.getSupplierSkuSnapshot(),
                                item.getUnitSymbolSnapshot(),
                                item.getQuantity(),
                                item.getUnitCost(),
                                item.getSubtotal()))
                        .toList());
        return new EmailAttachmentContent(reference.filename(), reference.mediaType(), renderer.render(data));
    }

    private static String businessName(Tenant tenant) {
        if (tenant == null) {
            return "OmniRetail";
        }
        return hasText(tenant.getLegalName()) ? tenant.getLegalName() : tenant.getName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
