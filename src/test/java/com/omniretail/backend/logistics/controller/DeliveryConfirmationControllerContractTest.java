package com.omniretail.backend.logistics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.logistics.dto.DeliveryConfirmationResponse;
import com.omniretail.backend.logistics.service.DeliveryConfirmationService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeliveryConfirmationControllerContractTest {

    private DeliveryConfirmationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DeliveryConfirmationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DeliveryConfirmationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void confirmUsesExistingPermissionAndForwardsBranchAndOrder() throws Exception {
        Method method = DeliveryConfirmationController.class
                .getDeclaredMethod("confirm", UUID.class, UUID.class);
        assertThat(method.getAnnotation(RequirePermission.class).value())
                .isEqualTo("logistics.dispatch.confirm");
        UUID branchId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant deliveredAt = Instant.parse("2030-01-02T03:04:05Z");
        when(service.confirm(branchId, orderId)).thenReturn(new DeliveryConfirmationResponse(
                orderId, OrderStatus.delivered, deliveredAt, false));

        mvc.perform(post("/logistics/deliveries/{orderId}/confirm", orderId)
                        .param("branchId", branchId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("delivered"))
                .andExpect(jsonPath("$.idempotent").value(false));

        verify(service).confirm(branchId, orderId);
    }
}
