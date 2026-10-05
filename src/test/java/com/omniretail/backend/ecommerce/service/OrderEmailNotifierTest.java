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
import tools.jackson.databind.json.JsonMapper;

class OrderEmailNotifierTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final OrderEmailNotifier notifier = new OrderEmailNotifier(publisher, JsonMapper.builder().build());
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void confirmedOrderWithSendModeEmitsTenantScopedEmailWithoutSensitiveData() {
        notifier.orderConfirmed(order("{\"emailMode\":\"send\",\"email\":\"cliente@example.com\"}"));

        EmailRequestedEvent event = captured();
        assertThat(event.message().tenantId()).isEqualTo(tenantId);
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.ORDER_CONFIRMATION);
        assertThat(event.message().purpose().scope()).isEqualTo(EmailPurpose.Scope.TENANT);
        assertThat(event.message().to()).isEqualTo("cliente@example.com");
        assertThat(event.message().body()).contains("WEB-123").doesNotContain("tracking-secreto");
    }

    @Test
    void dispatchedOrderEmitsDispatchNotification() {
        notifier.orderDispatched(order("{\"emailMode\":\"send\",\"email\":\" cliente@example.com \"}"));

        EmailRequestedEvent event = captured();
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.DISPATCH_NOTIFICATION);
        assertThat(event.message().to()).isEqualTo("cliente@example.com");
    }

    @Test
    void nothingIsSentWithoutAnEmailContact() {
        notifier.orderConfirmed(order(null));
        notifier.orderConfirmed(order("{\"emailMode\":\"not_applicable\"}"));
        notifier.orderConfirmed(order("{\"emailMode\":\"send\"}"));
        notifier.orderDispatched(order("no es json"));

        verify(publisher, never()).publishEvent(any(Object.class));
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
        return order;
    }
}
