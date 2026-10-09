package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.ecommerce.entity.Order;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderCustomerNameResolverTest {

    private final OrderCustomerNameResolver resolver =
            new OrderCustomerNameResolver(JsonMapper.builder().build());

    @Test
    void resolvesRegisteredCustomerBeforeEveryOrderSnapshot() {
        Order order = Order.builder()
                .guestCustomer("{\"name\":\"Invitado ecommerce\"}")
                .storePickupContact("{\"recipientName\":\"Persona que retira\"}")
                .deliveryAddress("{\"recipientName\":\"Persona que recibe\"}")
                .build();

        assertThat(resolver.resolve(order, "  Cliente registrado  ", "Fallback"))
                .isEqualTo("Cliente registrado");
    }

    @Test
    void keepsEcommerceGuestNameAheadOfContactSnapshots() {
        Order order = Order.builder()
                .guestCustomer("{\"name\":\"  Invitado ecommerce  \",\"email\":\"guest@example.com\"}")
                .storePickupContact("{\"recipientName\":\"Persona que retira\"}")
                .build();

        assertThat(resolver.resolve(order, null, "Fallback"))
                .isEqualTo("Invitado ecommerce");
    }

    @Test
    void fallsBackThroughPickupDeliveryAndCallerDefault() {
        Order pickup = Order.builder()
                .storePickupContact("{\"recipientName\":\"  Persona que retira  \"}")
                .deliveryAddress("{\"recipientName\":\"Persona que recibe\"}")
                .build();
        Order delivery = Order.builder()
                .deliveryAddress("{\"recipientName\":\"  Persona que recibe  \"}")
                .build();

        assertThat(resolver.resolve(pickup, null, "Cliente invitado"))
                .isEqualTo("Persona que retira");
        assertThat(resolver.resolve(delivery, null, "Cliente invitado"))
                .isEqualTo("Persona que recibe");
        assertThat(resolver.resolve(Order.builder().build(), null, "Cliente invitado"))
                .isEqualTo("Cliente invitado");
        assertThat(resolver.resolve(null, null, "Consumidor final"))
                .isEqualTo("Consumidor final");
    }
}
