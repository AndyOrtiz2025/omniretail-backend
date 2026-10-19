package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.shared.notification.EmailAttachmentContent;
import com.omniretail.backend.shared.notification.EmailAttachmentReference;
import com.omniretail.backend.shared.notification.EmailAttachmentResolver;
import com.omniretail.backend.shared.notification.EmailDeliveryException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Regenera el comprobante en cada intento de entrega del correo. */
@Component
@RequiredArgsConstructor
public class StorefrontReceiptPdfAttachmentResolver implements EmailAttachmentResolver {
    public static final String TYPE = "STOREFRONT_ORDER_RECEIPT_PDF";
    public static final String MEDIA_TYPE = "application/pdf";
    private final OrderRepository orders;
    private final StorefrontReceiptService receipts;

    @Override public boolean supports(String type) { return TYPE.equals(type); }

    @Override public EmailAttachmentContent resolve(UUID tenantId, EmailAttachmentReference reference) {
        Order order = orders.findByTenantIdAndId(tenantId, reference.resourceId())
                .orElseThrow(() -> new EmailDeliveryException(EmailDeliveryException.ATTACHMENT_UNAVAILABLE));
        return new EmailAttachmentContent(reference.filename(), reference.mediaType(), receipts.render(tenantId, order));
    }
}
