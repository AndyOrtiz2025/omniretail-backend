package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.shared.notification.EmailAttachmentReference;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorefrontReceiptPdfAttachmentResolverTest {

    @Test
    void resolvesThePdfFromTheReferencedTenantOrder() {
        OrderRepository orders = mock(OrderRepository.class);
        StorefrontReceiptService receipts = mock(StorefrontReceiptService.class);
        StorefrontReceiptPdfAttachmentResolver resolver = new StorefrontReceiptPdfAttachmentResolver(orders, receipts);
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Order order = mock(Order.class);
        EmailAttachmentReference reference = new EmailAttachmentReference(
                StorefrontReceiptPdfAttachmentResolver.TYPE, orderId, "pedido.pdf", "application/pdf");
        when(orders.findByTenantIdAndId(tenantId, orderId)).thenReturn(Optional.of(order));
        when(receipts.render(tenantId, order)).thenReturn(new byte[] {1, 2});

        var attachment = resolver.resolve(tenantId, reference);

        assertThat(resolver.supports(StorefrontReceiptPdfAttachmentResolver.TYPE)).isTrue();
        assertThat(resolver.supports("OTHER")).isFalse();
        assertThat(attachment.filename()).isEqualTo("pedido.pdf");
        assertThat(attachment.mediaType()).isEqualTo("application/pdf");
        assertThat(attachment.bytes()).containsExactly(1, 2);
    }
}
