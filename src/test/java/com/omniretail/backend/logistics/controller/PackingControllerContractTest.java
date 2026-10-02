package com.omniretail.backend.logistics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.logistics.dto.PackingVersionedRequest;
import com.omniretail.backend.logistics.dto.RegisterPackingLabelPrintRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.logistics.service.PackingService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PackingControllerContractTest {

    private PackingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PackingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PackingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void endpointsUseOnlyExistingPackingPermissions() throws Exception {
        assertPermission("queue", "logistics.packing.read", UUID.class);
        assertPermission("detail", "logistics.packing.read", UUID.class, UUID.class);
        assertPermission("savePreparation", "logistics.packing.prepare",
                UUID.class, UUID.class, SavePackingPreparationRequest.class);
        assertPermission("generateLabel", "logistics.packing.prepare",
                UUID.class, UUID.class, PackingVersionedRequest.class);
        assertPermission("registerLabelPrint", "logistics.packing.prepare",
                UUID.class, UUID.class, RegisterPackingLabelPrintRequest.class);
        assertPermission("finalizePacking", "logistics.packing.finalize",
                UUID.class, UUID.class, PackingVersionedRequest.class);
    }

    @Test
    void rejectsInvalidMutationBodiesBeforeCallingService() throws Exception {
        UUID packing = UUID.randomUUID();
        UUID branch = UUID.randomUUID();

        mvc.perform(patch("/logistics/packing/{packing}/preparation", packing)
                        .param("branchId", branch.toString())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":-1,"operationId":" ","checklist":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/logistics/packing/{packing}/label/print", packing)
                        .param("branchId", branch.toString())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"operationId":"print","labelGenerationId":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    private static void assertPermission(
            String methodName, String permission, Class<?>... parameterTypes) throws Exception {
        Method method = PackingController.class.getDeclaredMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(RequirePermission.class))
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo(permission);
    }
}
