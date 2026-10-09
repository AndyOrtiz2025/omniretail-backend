package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.entity.Order;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class OrderCustomerNameResolver {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE =
            new TypeReference<>() {};

    private final JsonMapper jsonMapper;

    public String resolve(Order order, String registeredCustomerName, String fallback) {
        String customerName = trimToNull(registeredCustomerName);
        if (customerName != null) {
            return customerName;
        }
        if (order == null) {
            return fallback;
        }

        String guestName = text(order.getGuestCustomer(), "name");
        if (guestName != null) {
            return guestName;
        }

        // El destinatario es quien recibe o retira el pedido, no necesariamente quien lo pagó.
        String pickupRecipient = text(order.getStorePickupContact(), "recipientName");
        if (pickupRecipient != null) {
            return pickupRecipient;
        }

        String deliveryRecipient = text(order.getDeliveryAddress(), "recipientName");
        return deliveryRecipient == null ? fallback : deliveryRecipient;
    }

    private String text(String json, String field) {
        if (json == null) {
            return null;
        }
        Map<String, Object> values = jsonMapper.readValue(json, JSON_OBJECT_TYPE);
        Object value = values.get(field);
        return value instanceof String text ? trimToNull(text) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
