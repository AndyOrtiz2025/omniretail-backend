package com.omniretail.backend.pos.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.pos.service.SaleService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SaleControllerValidationTest {
    private SaleService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SaleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SaleController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void rejectsMissingSaleFields() throws Exception {
        mvc.perform(post("/pos/sales").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsEmptyItemsAndPayments() throws Exception {
        mvc.perform(post("/pos/sales").contentType(APPLICATION_JSON).content("""
                {"branchId":"11111111-1111-1111-1111-111111111111",
                 "cashShiftId":"22222222-2222-2222-2222-222222222222",
                 "items":[],"payments":[]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedProductAndPaymentValues() throws Exception {
        mvc.perform(post("/pos/sales").contentType(APPLICATION_JSON).content("""
                {"branchId":"invalid","cashShiftId":"invalid",
                 "items":[{"productId":"invalid","quantity":0}],
                 "payments":[{"method":"cash","amount":-1}]}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedTrackingSelectionValues() throws Exception {
        mvc.perform(post("/pos/sales").contentType(APPLICATION_JSON).content("""
                {"branchId":"11111111-1111-1111-1111-111111111111",
                 "cashShiftId":"22222222-2222-2222-2222-222222222222",
                 "confirmationId":"33333333-3333-3333-3333-333333333333",
                 "items":[{"productId":"44444444-4444-4444-4444-444444444444",
                           "quantity":1,
                           "trackingSelections":[{
                             "productId":"44444444-4444-4444-4444-444444444444",
                             "locationId":"55555555-5555-5555-5555-555555555555",
                             "quantity":0,
                             "serialNumbers":[""]}]}],
                 "payments":[{"method":"cash","amount":1}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
}
