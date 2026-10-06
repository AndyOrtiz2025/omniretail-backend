package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Notificador por correo para ordenes de compra.
 * Proposito TENANT: se envia al proveedor usando la cuenta Gmail configurada por el tenant.
 * Si el proveedor no tiene correo configurado, no se dispara ningun evento.
 * Si el tenant no tiene configurado Gmail, el evento sera marcado como fallido por EmailDeliveryService
 * sin abortar la transaccion de la orden.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderEmailNotifier {

    private final ApplicationEventPublisher eventPublisher;

    public void notifyOrderApproved(
            PurchaseOrder order,
            Supplier supplier,
            Branch branch,
            List<PurchaseOrderItem> items) {
        if (order == null || supplier == null || supplier.getEmail() == null || supplier.getEmail().isBlank()) {
            return;
        }

        String recipient = supplier.getEmail().trim();
        String subject = "Orden de compra " + order.getNumber();
        String body = buildApprovedOrderBody(order, supplier, branch, items);

        eventPublisher.publishEvent(new EmailRequestedEvent(
                EmailMessage.text(order.getTenantId(), EmailPurpose.PURCHASE_ORDER, recipient, subject, body)));
    }

    private String buildApprovedOrderBody(
            PurchaseOrder order,
            Supplier supplier,
            Branch branch,
            List<PurchaseOrderItem> items) {
        String supplierName = supplier.getName() != null && !supplier.getName().isBlank()
                ? supplier.getName()
                : "Proveedor";
        StringBuilder sb = new StringBuilder();
        sb.append("Estimado(a) ").append(supplierName).append(":\n\n");
        sb.append("Le notificamos que la orden de compra ")
                .append(order.getNumber())
                .append(" ha sido aprobada.\n\n");

        sb.append("Detalles de la orden:\n");
        sb.append("- Numero: ").append(order.getNumber()).append("\n");
        if (order.getExpectedDate() != null) {
            sb.append("- Fecha esperada de entrega: ").append(order.getExpectedDate()).append("\n");
        }
        if (branch != null) {
            sb.append("- Sucursal de recepcion: ").append(branch.getName()).append("\n");
            if (branch.getAddress() != null && !branch.getAddress().isBlank()) {
                sb.append("- Direccion de entrega: ").append(branch.getAddress()).append("\n");
            }
        }

        if (items != null && !items.isEmpty()) {
            sb.append("\nProductos solicitados:\n");
            for (PurchaseOrderItem item : items) {
                String unit = item.getUnitSymbolSnapshot() != null && !item.getUnitSymbolSnapshot().isBlank()
                        ? item.getUnitSymbolSnapshot()
                        : "unidades";
                sb.append("- ")
                        .append(item.getQuantity())
                        .append(" ")
                        .append(unit)
                        .append(" | ")
                        .append(item.getProductNameSnapshot())
                        .append(" (SKU: ")
                        .append(item.getProductSkuSnapshot())
                        .append(")")
                        .append(" | Costo unitario: Q")
                        .append(item.getUnitCost())
                        .append(" | Subtotal: Q")
                        .append(item.getSubtotal())
                        .append("\n");
            }
        }

        sb.append("\nTotal: Q").append(order.getTotal() != null ? order.getTotal() : "0.00").append("\n\n");

        if (order.getNotes() != null && !order.getNotes().isBlank()) {
            sb.append("Notas adicionales:\n").append(order.getNotes()).append("\n\n");
        }

        sb.append("Saludos cordiales.");
        return sb.toString();
    }
}
