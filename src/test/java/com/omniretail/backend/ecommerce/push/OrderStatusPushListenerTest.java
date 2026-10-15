package com.omniretail.backend.ecommerce.push;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.*;

class OrderStatusPushListenerTest {

    @Test
    void forwardsOrderStatusEventToExpoService() {
        ExpoPushService expoService = mock(ExpoPushService.class);
        OrderStatusPushListener listener =
                new OrderStatusPushListener(expoService);

        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderStatus.confirmed
        );

        listener.onOrderStatusChanged(event);

        verify(expoService, times(1)).send(event);
        verifyNoMoreInteractions(expoService);
    }
}
