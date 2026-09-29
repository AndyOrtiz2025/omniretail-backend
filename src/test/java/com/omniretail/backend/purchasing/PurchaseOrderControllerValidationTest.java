package com.omniretail.backend.purchasing;

import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.purchasing.controller.PurchaseOrderController;
import com.omniretail.backend.purchasing.service.PurchaseOrderService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PurchaseOrderControllerValidationTest {
    private PurchaseOrderService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PurchaseOrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PurchaseOrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void rejectsMissingSupplierAndLines() throws Exception {
        mvc.perform(post("/purchasing/orders").contentType(APPLICATION_JSON)
                .content("{\"supplierId\":null,\"branchId\":null,\"items\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidLineAmounts() throws Exception {
        mvc.perform(post("/purchasing/orders").contentType(APPLICATION_JSON).content("""
                {"supplierId":"11111111-1111-1111-1111-111111111111","branchId":"11111111-1111-1111-1111-111111111112","items":[{"productId":"11111111-1111-1111-1111-111111111113","quantity":1,"unitCost":1.001}]}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
