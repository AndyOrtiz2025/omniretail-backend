package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class StorefrontReceiptServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final StorefrontReceiptPdfRenderer renderer = mock(StorefrontReceiptPdfRenderer.class);
    private final StorefrontReceiptService service = new StorefrontReceiptService(
            tenants, orders, items, JsonMapper.builder().build(), renderer);

    @Test
    void rendersTheReceiptWithItemsAndDeliveryAddress() {
        UUID tenantId = UUID.randomUUID();
        Order order = order("{\"recipientName\":\"Ana López\",\"line1\":\"5a calle\",\"city\":\"Guatemala\"}");
        List<OrderItem> orderItems = List.of(mock(OrderItem.class));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.builder().name("Ferretería").build()));
        when(items.findByOrderId(order.getId())).thenReturn(orderItems);
        when(renderer.render(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.same(order),
                org.mockito.ArgumentMatchers.same(orderItems), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new byte[] {1, 2, 3});

        assertThat(service.render(tenantId, order)).containsExactly(1, 2, 3);

        ArgumentCaptor<Map<String, Object>> address = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(org.mockito.ArgumentMatchers.eq("Ferretería"), org.mockito.ArgumentMatchers.same(order),
                org.mockito.ArgumentMatchers.same(orderItems), address.capture());
        assertThat(address.getValue()).containsEntry("recipientName", "Ana López").containsEntry("line1", "5a calle");
    }

    @Test
    void rendersStorePickupOrdersWithAnEmptyAddress() {
        UUID tenantId = UUID.randomUUID();
        Order order = order(null);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.builder().name("Ferretería").build()));
        when(items.findByOrderId(order.getId())).thenReturn(List.of());
        when(renderer.render(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.same(order),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(new byte[] {4});

        service.render(tenantId, order);

        ArgumentCaptor<Map<String, Object>> address = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.same(order),
                org.mockito.ArgumentMatchers.anyList(), address.capture());
        assertThat(address.getValue()).isEmpty();
    }

    private static Order order(String deliveryAddress) {
        Order order = Order.builder().deliveryAddress(deliveryAddress).build();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        return order;
    }
}
