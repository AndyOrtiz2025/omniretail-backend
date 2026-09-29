package com.omniretail.backend.pos.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.pos.service.CashMovementService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CashMovementControllerValidationTest {
    private CashMovementService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CashMovementService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CashMovementController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void rejectsInvalidAmountTypeAndReason() throws Exception {
        mvc.perform(post("/pos/cash-movements").contentType(APPLICATION_JSON).content("""
                {"cashShiftId":"11111111-1111-1111-1111-111111111111","type":null,"amount":null,"reason":""}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsNegativeAndOverPreciseAmount() throws Exception {
        for (String amount : new String[]{"-1", "1.001", "10000000000"}) {
            mvc.perform(post("/pos/cash-movements").contentType(APPLICATION_JSON).content("""
                    {"cashShiftId":"11111111-1111-1111-1111-111111111111","type":"in","amount":%s,"reason":"Ajuste"}
                    """.formatted(amount)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
