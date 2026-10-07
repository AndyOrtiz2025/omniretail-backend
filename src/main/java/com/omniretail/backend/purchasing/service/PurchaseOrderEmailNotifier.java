package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.shared.notification.EmailAttachmentReference;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Notificador por correo para ordenes de compra aprobadas.
 * Proposito TENANT: se envia al proveedor usando la cuenta Gmail configurada por el tenant.
 *
 * <p>El correo es un resumen (texto plano + HTML) con el PDF de la orden adjunto por REFERENCIA: el PDF se genera
 * al momento de enviar (y de reintentar) y nunca viaja ni se guarda en el payload. Una aprobacion produce un unico
 * evento; si el proveedor no tiene correo no se dispara nada. Si el tenant no tiene Gmail configurado, o falla el
 * SMTP o la generacion del PDF, el envio queda FAILED en {@code email_delivery} (reintentable cuando aplica) sin
 * abortar ni revertir la aprobacion.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderEmailNotifier {

    private final ApplicationEventPublisher eventPublisher;
    private final TenantRepository tenantRepository;

    public void notifyOrderApproved(
            PurchaseOrder order,
            Supplier supplier,
            Branch branch,
            List<PurchaseOrderItem> items) {
        if (order == null || supplier == null || supplier.getEmail() == null || supplier.getEmail().isBlank()) {
            return;
        }

        String recipient = supplier.getEmail().trim();
        PurchaseOrderEmailTemplate.Content content = PurchaseOrderEmailTemplate.render(
                businessName(order),
                order,
                supplier,
                branch,
                items == null ? 0 : items.size());
        EmailAttachmentReference pdf = new EmailAttachmentReference(
                PurchaseOrderPdfAttachmentResolver.TYPE,
                order.getId(),
                attachmentFilename(order),
                PurchaseOrderPdfAttachmentResolver.MEDIA_TYPE);

        eventPublisher.publishEvent(new EmailRequestedEvent(EmailMessage.withAttachments(
                order.getTenantId(),
                EmailPurpose.PURCHASE_ORDER,
                recipient,
                content.subject(),
                content.plainText(),
                content.html(),
                List.of(pdf))));
    }

    private String businessName(PurchaseOrder order) {
        if (order.getTenantId() == null) {
            return null;
        }
        return tenantRepository.findById(order.getTenantId())
                .map(tenant -> tenant.getLegalName() != null && !tenant.getLegalName().isBlank()
                        ? tenant.getLegalName()
                        : tenant.getName())
                .orElse(null);
    }

    private static String attachmentFilename(PurchaseOrder order) {
        String number = order.getNumber() == null ? "orden" : order.getNumber().replaceAll("[^A-Za-z0-9-]+", "-");
        return number + "-orden-compra.pdf";
    }
}
