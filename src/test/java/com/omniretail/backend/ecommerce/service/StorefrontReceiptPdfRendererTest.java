package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorefrontReceiptPdfRendererTest {

    private final StorefrontReceiptPdfRenderer renderer = new StorefrontReceiptPdfRenderer();

    @Test
    void generatesAPdfForAnOrderWithItemsAndAddress() {
        Order order = mock(Order.class);
        when(order.getOrderNumber()).thenReturn("WEB-123");
        when(order.getCreatedAt()).thenReturn(Instant.parse("2026-10-18T12:00:00Z"));
        when(order.getStatus()).thenReturn(OrderStatus.confirmed);
        when(order.getSubtotal()).thenReturn(new BigDecimal("100.00"));
        when(order.getShippingTotal()).thenReturn(BigDecimal.ZERO);
        when(order.getTotal()).thenReturn(new BigDecimal("100.00"));
        OrderItem item = mock(OrderItem.class);
        when(item.getNameSnapshot()).thenReturn("Taladro");
        when(item.getSkuSnapshot()).thenReturn("TAL-001");
        when(item.getQuantity()).thenReturn(BigDecimal.ONE);
        when(item.getUnitPrice()).thenReturn(new BigDecimal("100.00"));
        when(item.getSubtotal()).thenReturn(new BigDecimal("100.00"));

        byte[] receipt = renderer.render("Ferretería Centro", order, List.of(item),
                Map.of("recipientName", "Ana López", "line1", "5a calle", "city", "Guatemala"));

        assertThat(receipt).isNotEmpty();
        assertThat(new String(receipt, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }
}
