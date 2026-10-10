package com.omniretail.backend.logistics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.logistics.dto.LogisticsHistoryDeliveryMethod;
import com.omniretail.backend.logistics.dto.LogisticsHistoryRowResponse;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.service.LogisticsHistoryService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LogisticsHistoryControllerContractTest {

    private LogisticsHistoryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LogisticsHistoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LogisticsHistoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void endpointsUseHistoryReadPermission() throws Exception {
        permission(
                "search",
                UUID.class,
                String.class,
                String.class,
                String.class,
                String.class,
                LocalDate.class,
                LocalDate.class,
                int.class,
                int.class);
        permission("detail", UUID.class, PickingSourceType.class, UUID.class);
    }

    @Test
    void listBindsFiltersAndReturnsThePageContract() throws Exception {
        UUID branchId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        LogisticsHistoryRowResponse row = new LogisticsHistoryRowResponse(
                PickingSourceType.order,
                sourceId,
                sourceId,
                "POS-001",
                LogisticsHistoryDeliveryMethod.store_pickup,
                "delivered",
                "Ana",
                "+50255555555",
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        when(service.search(
                        branchId,
                        "ana",
                        "delivered",
                        "received",
                        "store_pickup",
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-09"),
                        1,
                        25))
                .thenReturn(new PageResponse<>(List.of(row), 2, 25, 26, 2));

        mvc.perform(get("/logistics/history")
                        .param("branchId", branchId.toString())
                        .param("search", "ana")
                        .param("status", "delivered")
                        .param("transferStatus", "received")
                        .param("deliveryMethod", "store_pickup")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-09")
                        .param("page", "1")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceType").value("order"))
                .andExpect(jsonPath("$.items[0].orderReference").value("POS-001"))
                .andExpect(jsonPath("$.items[0].operationalStatus").value("delivered"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalItems").value(26));
        verify(service).search(
                branchId,
                "ana",
                "delivered",
                "received",
                "store_pickup",
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-09"),
                1,
                25);
    }

    @Test
    void rejectsInvalidTransferStatusWithBadRequest() throws Exception {
        UUID branchId = UUID.randomUUID();
        when(service.search(
                        branchId,
                        null,
                        null,
                        "foo",
                        null,
                        null,
                        null,
                        0,
                        20))
                .thenThrow(new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "LOGISTICS_HISTORY_TRANSFER_STATUS_INVALID",
                        "El estado de traslado no es v├ílido."));

        mvc.perform(get("/logistics/history")
                        .param("branchId", branchId.toString())
                        .param("transferStatus", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOGISTICS_HISTORY_TRANSFER_STATUS_INVALID"))
                .andExpect(jsonPath("$.message").value("El estado de traslado no es v├ílido."));
    }

    private static void permission(String method, Class<?>... parameterTypes) throws Exception {
        Method declared = LogisticsHistoryController.class.getDeclaredMethod(method, parameterTypes);
        assertThat(declared.getAnnotation(RequirePermission.class).value())
                .isEqualTo("logistics.history.read");
    }
}
