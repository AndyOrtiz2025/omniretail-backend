package com.omniretail.backend.logistics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.ConfirmTransferDispatchRequest;
import com.omniretail.backend.logistics.service.DispatchService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DispatchControllerContractTest {
    private DispatchService service; private MockMvc mvc;
    @BeforeEach void setUp() { service=mock(DispatchService.class); mvc=MockMvcBuilders.standaloneSetup(new DispatchController(service)).setControllerAdvice(new GlobalExceptionHandler()).build(); }
    @Test void endpointsUseExistingDispatchPermissions() throws Exception {
        permission("queue", "logistics.dispatch.read", UUID.class);
        permission("detail", "logistics.dispatch.read", UUID.class, UUID.class);
        permission("preparedDetail", "logistics.dispatch.read", UUID.class, UUID.class);
        permission("confirm", "logistics.dispatch.confirm", UUID.class, UUID.class, ConfirmDispatchRequest.class);
        permission("transferDetail", "logistics.dispatch.read", UUID.class, UUID.class);
        permission(
                "confirmTransfer",
                "logistics.dispatch.confirm",
                UUID.class,
                UUID.class,
                ConfirmTransferDispatchRequest.class);
    }
    @Test void invalidTransferConfirmRequestIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/logistics/dispatch/transfers/{transfer}/confirm", UUID.randomUUID())
                        .param("branchId", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON)
                        .content("{\"operationId\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void invalidConfirmRequestIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/logistics/dispatch/{order}/confirm", UUID.randomUUID()).param("branchId", UUID.randomUUID().toString()).contentType(APPLICATION_JSON).content("{\"operationId\":\" \",\"packages\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    private static void permission(String method,String value,Class<?>... types) throws Exception { Method declared=DispatchController.class.getDeclaredMethod(method,types); assertThat(declared.getAnnotation(RequirePermission.class).value()).isEqualTo(value); }
}
