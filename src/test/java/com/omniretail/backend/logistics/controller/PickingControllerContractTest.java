package com.omniretail.backend.logistics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.logistics.dto.CreatePickingIncidentRequest;
import com.omniretail.backend.logistics.dto.ReleasePickingRequest;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PickingControllerContractTest {

    private PickingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PickingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PickingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void endpointsUseOnlyTheExistingPickingPermissions() throws Exception {
        assertPermission("queue", "logistics.picking.read", UUID.class);
        assertPermission("detail", "logistics.picking.read", UUID.class, UUID.class);
        assertPermission("assign", "logistics.picking.start", UUID.class, UUID.class);
        assertPermission("release", "logistics.picking.start",
                UUID.class, UUID.class, ReleasePickingRequest.class);
        assertPermission("updateItem", "logistics.picking.start",
                UUID.class, UUID.class, UUID.class, UpdatePickingItemRequest.class);
        assertPermission("createIncident", "logistics.picking.start",
                UUID.class, UUID.class, CreatePickingIncidentRequest.class);
        assertPermission("resolveIncident", "logistics.picking.start",
                UUID.class, UUID.class, UUID.class);
        assertPermission("complete", "logistics.picking.complete", UUID.class, UUID.class);
    }

    @Test
    void rejectsInvalidMutationBodiesBeforeCallingTheService() throws Exception {
        UUID picking = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID branch = UUID.randomUUID();

        mvc.perform(patch("/logistics/picking/{picking}/items/{item}", picking, item)
                        .param("branchId", branch.toString())
                        .contentType(APPLICATION_JSON)
                        .content("{\"pickedQuantity\":-1,\"operationId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/logistics/picking/{picking}/release", picking)
                        .param("branchId", branch.toString())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    private static void assertPermission(
            String methodName, String permission, Class<?>... parameterTypes) throws Exception {
        Method method = PickingController.class.getDeclaredMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(RequirePermission.class))
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo(permission);
    }
}
