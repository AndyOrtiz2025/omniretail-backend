package com.omniretail.backend.ecommerce.push;

import com.omniretail.backend.ecommerce.repository.CustomerPushDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.omniretail.backend.ecommerce.entity.CustomerPushDevice;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ExpoPushServiceTest {

    private CustomerPushDeviceRepository repository;
    private ExpoPushService service;

    private UUID tenantId;
    private UUID customerId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerPushDeviceRepository.class);
        service = new ExpoPushService(
                repository,
                JsonMapper.builder().build()
        );

        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        orderId = UUID.randomUUID();
    }

    @Test
    void skipsNotificationWithoutCustomer() {
        var event = new OrderStatusChangedEvent(
                tenantId, null, orderId, null
        );

        service.send(event);

        verifyNoInteractions(repository);
    }

    @Test
    void skipsNotificationWithoutRegisteredDevices() {
        when(repository.findByTenantIdAndCustomerIdAndEnabledTrue(
                tenantId, customerId
        )).thenReturn(List.of());

        var event = new OrderStatusChangedEvent(
                tenantId, customerId, orderId, null
        );

        service.send(event);

        verify(repository).findByTenantIdAndCustomerIdAndEnabledTrue(
                tenantId, customerId
        );
        verifyNoMoreInteractions(repository);
    }

    @Test
    void propagatesRepositoryFailure() {
        when(repository.findByTenantIdAndCustomerIdAndEnabledTrue(
                tenantId, customerId
        )).thenThrow(new IllegalStateException("Database unavailable"));

        var event = new OrderStatusChangedEvent(
                tenantId, customerId, orderId, null
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.send(event)
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendsNotificationWhenExpoRespondsSuccessfully() throws Exception {
        verifyExpoResponse(200);
    }

    @Test
    void handlesExpoServerError() throws Exception {
        verifyExpoResponse(500);
    }

    @Test
    void preservesThreadInterruptionWhenHttpRequestIsInterrupted() throws Exception {
        HttpClient client = mock(HttpClient.class);

        when(client.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenThrow(new InterruptedException("Interrupted"));

        CustomerPushDevice device = mock(CustomerPushDevice.class);
        when(device.getPushToken()).thenReturn("ExpoPushToken[android123]");

        when(repository.findByTenantIdAndCustomerIdAndEnabledTrue(
                tenantId, customerId
        )).thenReturn(List.of(device));

        var testedService = new ExpoPushService(
                repository,
                JsonMapper.builder().build(),
                () -> client
        );

        try {
            testedService.send(new OrderStatusChangedEvent(
                    tenantId, customerId, orderId, OrderStatus.confirmed
            ));

            org.assertj.core.api.Assertions.assertThat(
                    Thread.currentThread().isInterrupted()
            ).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(client).close();
    }

    private void verifyExpoResponse(int statusCode) throws Exception {
        HttpClient client = mock(HttpClient.class);

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(statusCode);

        when(client.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);

        CustomerPushDevice device = mock(CustomerPushDevice.class);
        when(device.getPushToken()).thenReturn("ExpoPushToken[android123]");

        when(repository.findByTenantIdAndCustomerIdAndEnabledTrue(
                tenantId, customerId
        )).thenReturn(List.of(device));

        var testedService = new ExpoPushService(
                repository,
                JsonMapper.builder().build(),
                () -> client
        );

        testedService.send(new OrderStatusChangedEvent(
                tenantId, customerId, orderId, OrderStatus.confirmed
        ));

        org.mockito.ArgumentCaptor<HttpRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);

        verify(client).send(
                requestCaptor.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );

        org.assertj.core.api.Assertions.assertThat(
                requestCaptor.getValue().uri().toString()
        ).isEqualTo("https://exp.host/--/api/v2/push/send");

        verify(client).close();
    }

}
