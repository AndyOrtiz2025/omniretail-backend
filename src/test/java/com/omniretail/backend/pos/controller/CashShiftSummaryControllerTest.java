package com.omniretail.backend.pos.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.pos.dto.CashShiftSummaryResponse;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.service.CashShiftSummaryService;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

class CashShiftSummaryControllerTest {

    @Test
    void summaryEndpointDelegatesToService() {
        CashShiftSummaryService summaryService = mock(CashShiftSummaryService.class);
        CashShiftSummaryController controller = new CashShiftSummaryController(summaryService);
        UUID cashShiftId = UUID.randomUUID();
        CashShiftSummaryResponse expected = new CashShiftSummaryResponse(
                cashShiftId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "POS-01",
                CashShiftStatus.open,
                Instant.parse("2026-09-29T12:00:00Z"),
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                null,
                null);
        when(summaryService.getSummary(cashShiftId)).thenReturn(expected);

        CashShiftSummaryResponse response = controller.summary(cashShiftId);

        assertSame(expected, response);
        verify(summaryService).getSummary(cashShiftId);
    }

    @Test
    void summaryEndpointRequiresExistingCashReadPermission() throws NoSuchMethodException {
        Method method = CashShiftSummaryController.class.getMethod("summary", UUID.class);

        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertEquals("pos.cash.read", permission.value());
        assertArrayEquals(new String[] {"/{cashShiftId}/summary"}, mapping.value());
    }
}
