package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.service.CustomerPushDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CustomerPushDeviceControllerTest {

    private CustomerPushDeviceService service;
    private CustomerPushDeviceController controller;

    @BeforeEach
    void setUp() {
        service = mock(CustomerPushDeviceService.class);
        controller = new CustomerPushDeviceController(service);
    }

    @Test
    void registersDeviceSuccessfully() {
        var request = new CustomerPushDeviceController.RegisterDeviceRequest(
                "ExpoPushToken[android123]", "android"
        );

        var response = controller.register(request);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        verify(service).register(
                "ExpoPushToken[android123]", "android"
        );
    }

    @Test
    void unregistersDeviceSuccessfully() {
        var request = new CustomerPushDeviceController.UnregisterDeviceRequest(
                "ExpoPushToken[android123]"
        );

        var response = controller.unregister(request);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        verify(service).unregister("ExpoPushToken[android123]");
    }
}
