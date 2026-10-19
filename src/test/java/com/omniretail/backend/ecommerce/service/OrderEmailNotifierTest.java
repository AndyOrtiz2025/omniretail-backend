package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class OrderEmailNotifierTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final OrderEmailNotifier notifier = new OrderEmailNotifier(publisher, JsonMapper.builder().build());
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void confirmedOrderWithSendModeEmitsTenantScopedEmailWithoutSensitiveData() {
        Order order = order("{\"emailMode\":\"send\",\"email\":\"cliente@example.com\"}");
        notifier.orderConfirmed(order);

        EmailRequestedEvent event = captured();
        assertThat(event.message().tenantId()).isEqualTo(tenantId);
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.ORDER_CONFIRMATION);
        assertThat(event.message().purpose().scope()).isEqualTo(EmailPurpose.Scope.TENANT);
        assertThat(event.message().to()).isEqualTo("cliente@example.com");
        assertThat(event.message().body()).contains("WEB-123").doesNotContain("tracking-secreto");
        assertThat(event.message().html()).contains("Pedido confirmado", "<strong>WEB-123</strong>")
                .doesNotContain("tracking-secreto");
        assertThat(event.message().attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.type()).isEqualTo(StorefrontReceiptPdfAttachmentResolver.TYPE);
            assertThat(attachment.resourceId()).isEqualTo(order.getId());
            assertThat(attachment.filename()).isEqualTo("WEB-123-comprobante.pdf");
            assertThat(attachment.mediaType()).isEqualTo(StorefrontReceiptPdfAttachmentResolver.MEDIA_TYPE);
        });
    }

    @Test
    void dispatchedOrderEmitsDispatchNotification() {
        notifier.orderDispatched(order("{\"emailMode\":\"send\",\"email\":\" cliente@example.com \"}"));

        EmailRequestedEvent event = captured();
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.DISPATCH_NOTIFICATION);
        assertThat(event.message().to()).isEqualTo("cliente@example.com");
        assertThat(event.message().body()).contains("WEB-123", "va en camino");
        assertThat(event.message().html()).contains("<strong>WEB-123</strong>", "va en camino");
    }

    @Test
    void cancelledOrderEmitsAStatusChangeEmailThatSaysItWasCancelled() {
        notifier.orderCancelled(order("{\"emailMode\":\"send\",\"email\":\"cliente@example.com\"}"));

        EmailRequestedEvent event = captured();
        assertThat(event.message().tenantId()).isEqualTo(tenantId);
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.ORDER_STATUS_CHANGED);
        assertThat(event.message().purpose().scope()).isEqualTo(EmailPurpose.Scope.TENANT);
        assertThat(event.message().to()).isEqualTo("cliente@example.com");
        assertThat(event.message().subject()).contains("WEB-123").contains("cancelado");
        assertThat(event.message().body()).contains("WEB-123").contains("cancelado")
                .doesNotContain("confirmado").doesNotContain("tracking-secreto");
        assertThat(event.message().html()).contains("Pedido cancelado", "<strong>WEB-123</strong>",
                "comunícate con la tienda").doesNotContain("tracking-secreto");
    }

    @Test
    void nothingIsSentWithoutAnEmailContact() {
        notifier.orderConfirmed(order(null));
        notifier.orderConfirmed(order("{\"emailMode\":\"not_applicable\"}"));
        notifier.orderConfirmed(order("{\"emailMode\":\"send\"}"));
        notifier.orderDispatched(order("no es json"));
        notifier.orderCancelled(order(null));
        notifier.orderCancelled(order("{\"emailMode\":\"not_applicable\"}"));

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void confirmedOrderWithoutPersistenceIdSendsTheEmailWithoutAnAttachment() {
        Order order = Order.builder()
                .orderNumber("WEB-123")
                .trackingToken("tracking-secreto")
                .notificationContact("{\"emailMode\":\"send\",\"email\":\"cliente@example.com\"}")
                .build();
        order.setTenantId(tenantId);

        notifier.orderConfirmed(order);

        assertThat(captured().message().attachments()).isEmpty();
    }

    private EmailRequestedEvent captured() {
        ArgumentCaptor<EmailRequestedEvent> captor = ArgumentCaptor.forClass(EmailRequestedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private Order order(String notificationContact) {
        Order order = Order.builder()
                .orderNumber("WEB-123")
                .trackingToken("tracking-secreto")
                .notificationContact(notificationContact)
                .build();
        order.setTenantId(tenantId);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        return order;
    }
}
