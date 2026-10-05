package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Correos del pedido al contacto de {@code Order.notificationContact} (solo con {@code emailMode = "send"}).
 * Propositos TENANT: salen con la cuenta Gmail del negocio y, si no esta configurada, quedan FAILED sin
 * usar el remitente de plataforma. Se llama dentro de la transaccion del pedido; el cuerpo solo lleva el
 * numero de pedido (ni tokens de seguimiento ni datos de pago o direccion).
 */
@Component
@RequiredArgsConstructor
public class OrderEmailNotifier {

    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;

    public void orderConfirmed(Order order) {
        notify(order, EmailPurpose.ORDER_CONFIRMATION,
                "Tu pedido " + order.getOrderNumber() + " fue confirmado",
                "Hola:\n\nRecibimos tu pedido %s y ya lo estamos preparando. Te avisaremos cuando salga a entrega.\n\nGracias por tu compra."
                        .formatted(order.getOrderNumber()));
    }

    public void orderDispatched(Order order) {
        notify(order, EmailPurpose.DISPATCH_NOTIFICATION,
                "Tu pedido " + order.getOrderNumber() + " va en camino",
                "Hola:\n\nTu pedido %s ya fue despachado y va en camino.\n\nGracias por tu compra."
                        .formatted(order.getOrderNumber()));
    }

    private void notify(Order order, EmailPurpose purpose, String subject, String body) {
        String recipient = recipientOf(order);
        if (recipient == null) {
            return;
        }
        eventPublisher.publishEvent(new EmailRequestedEvent(
                EmailMessage.text(order.getTenantId(), purpose, recipient, subject, body)));
    }

    private String recipientOf(Order order) {
        String raw = order.getNotificationContact();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode contact = jsonMapper.readTree(raw);
            if (!"send".equals(contact.path("emailMode").asText(""))) {
                return null;
            }
            String email = contact.path("email").asText("");
            return email.isBlank() ? null : email.trim();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
