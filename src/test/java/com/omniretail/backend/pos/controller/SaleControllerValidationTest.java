package com.omniretail.backend.pos.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.dto.PosSalesHistoryPageResponse;
import com.omniretail.backend.pos.dto.PosSalesHistorySummaryResponse;
import com.omniretail.backend.pos.dto.SaleConfirmationResponse;
import com.omniretail.backend.pos.dto.SaleDocumentResponse;
import com.omniretail.backend.pos.entity.SaleDocumentType;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.service.PosSalesHistoryService;
import com.omniretail.backend.pos.service.SaleService;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SaleControllerValidationTest {
    private SaleService service;
    private PosSalesHistoryService historyService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SaleService.class);
        historyService = mock(PosSalesHistoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SaleController(service, historyService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void historyRequiresBranch() throws Exception {
        mvc.perform(get("/pos/sales/history"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(historyService);
    }

    @Test
    void historyBindsSupportedQueryParametersAndReturnsPageResponse() throws Exception {
        UUID branchId = UUID.randomUUID();
        when(historyService.search(
                        eq(branchId),
                        eq("POS-001"),
                        eq(LocalDate.parse("2026-10-01")),
                        eq(LocalDate.parse("2026-10-02")),
                        eq(SaleStatus.completed),
                        eq(DeliveryMethod.home_delivery),
                        eq(OrderStatus.picking),
                        any(Pageable.class)))
                .thenReturn(new PosSalesHistoryPageResponse(
                        List.of(),
                        1,
                        25,
                        0,
                        0,
                        new PosSalesHistorySummaryResponse(0, 0, 0, 0, 0)));

        mvc.perform(get("/pos/sales/history")
                        .param("branchId", branchId.toString())
                        .param("search", "POS-001")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-02")
                        .param("status", "completed")
                        .param("deliveryMethod", "home_delivery")
                        .param("operationalStatus", "picking")
                        .param("page", "0")
                        .param("size", "25")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(25))
                .andExpect(jsonPath("$.summary.total").value(0));

        verify(historyService).search(
                eq(branchId),
                eq("POS-001"),
                eq(LocalDate.parse("2026-10-01")),
                eq(LocalDate.parse("2026-10-02")),
                eq(SaleStatus.completed),
                eq(DeliveryMethod.home_delivery),
                eq(OrderStatus.picking),
                any(Pageable.class));
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
    void createResponsePreservesSaleFieldsAndAddsConfirmationDetails() throws Exception {
        UUID saleId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        when(service.create(org.mockito.ArgumentMatchers.any())).thenReturn(new SaleConfirmationResponse(
                saleId,
                "POS-001",
                branchId,
                shiftId,
                new BigDecimal("20.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("20.00"),
                Instant.parse("2026-10-02T12:00:00Z"),
                SaleStatus.completed,
                null,
                null,
                new SaleDocumentResponse(SaleDocumentType.ticket, null, null, null),
                List.of(),
                List.of(),
                List.of(),
                null,
                false));

        mvc.perform(post("/pos/sales").contentType(APPLICATION_JSON).content("""
                {"branchId":"%s","cashShiftId":"%s",
                 "items":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":1}],
                 "payments":[{"method":"cash","amount":20}],
                 "confirmationId":"22222222-2222-2222-2222-222222222222"}
                """.formatted(branchId, shiftId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(saleId.toString()))
                .andExpect(jsonPath("$.number").value("POS-001"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.document.type").value("ticket"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.inventoryEffects").isArray())
                .andExpect(jsonPath("$.idempotent").value(false));
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
