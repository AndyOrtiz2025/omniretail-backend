package com.omniretail.backend.pos.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.pos.service.CashShiftService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Validacion MVC sin base de datos; la seguridad se prueba en CashShiftControllerTest. */
class CashShiftControllerValidationTest {

    private CashShiftService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CashShiftService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CashShiftController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-0.01", "0.001", "10000000000"})
    void rejectsInvalidOpeningAmounts(String amount) throws Exception {
        mvc.perform(post("/pos/cash-shifts/open").contentType(APPLICATION_JSON).content("""
                {"branchId":"11111111-1111-1111-1111-111111111111","registerCode":"A","openingAmount":%s}
                """.formatted(amount)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "1.001", "10000000000"})
    void rejectsInvalidCountedAmounts(String amount) throws Exception {
        mvc.perform(post("/pos/cash-shifts/close").contentType(APPLICATION_JSON).content("""
                {"cashShiftId":"11111111-1111-1111-1111-111111111111","countedAmount":%s}
                """.formatted(amount)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"openingAmount\":0,\"registerCode\":\" \"}",
            "{\"branchId\":\"invalid\",\"registerCode\":\"A\",\"openingAmount\":0}"})
    void rejectsMissingFieldsAndMalformedIds(String body) throws Exception {
        mvc.perform(post("/pos/cash-shifts/open").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
