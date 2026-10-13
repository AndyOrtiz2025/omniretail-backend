package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.shared.notification.EmailLayout;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Correos del pedido al contacto de {@code Order.notificationContact} (solo con {@code emailMode = "send"}).
 * Propositos TENANT: salen con la cuenta Gmail del negocio y, si no esta configurada, quedan FAILED sin
 * usar el remitente de plataforma. Se llama dentro de la transaccion del pedido; el cuerpo solo lleva el
 * numero de pedido, en texto plano y HTML (ni tokens de seguimiento ni datos de pago o direccion).
 */
@Component
@RequiredArgsConstructor
public class OrderEmailNotifier {

    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;

    public void orderConfirmed(Order order) {
        String number = order.getOrderNumber();
        notify(order, EmailPurpose.ORDER_CONFIRMATION,
                "Tu pedido " + number + " fue confirmado",
                "Hola:\n\nRecibimos tu pedido %s y ya lo estamos preparando. Te avisaremos cuando salga a entrega.\n\nGracias por tu compra."
                        .formatted(number),
                EmailLayout.render(null, "Pedido confirmado", List.of(
                        EmailLayout.paragraph("Hola:"),
                        EmailLayout.paragraph("Recibimos tu pedido ", number,
                                " y ya lo estamos preparando. Te avisaremos cuando salga a entrega."),
                        EmailLayout.paragraph("Gracias por tu compra."))));
    }

    public void orderDispatched(Order order) {
        String number = order.getOrderNumber();
        notify(order, EmailPurpose.DISPATCH_NOTIFICATION,
                "Tu pedido " + number + " va en camino",
                "Hola:\n\nTu pedido %s ya fue despachado y va en camino.\n\nGracias por tu compra."
                        .formatted(number),
                EmailLayout.render(null, "Tu pedido va en camino", List.of(
                        EmailLayout.paragraph("Hola:"),
                        EmailLayout.paragraph("Tu pedido ", number, " ya fue despachado y va en camino."),
                        EmailLayout.paragraph("Gracias por tu compra."))));
    }

    public void orderCancelled(Order order) {
        String number = order.getOrderNumber();
        notify(order, EmailPurpose.ORDER_STATUS_CHANGED,
                "Tu pedido " + number + " fue cancelado",
                "Hola:\n\nTu pedido %s fue cancelado. Si tienes dudas o ya realizaste un pago, comunícate con la tienda.\n\nGracias."
                        .formatted(number),
                EmailLayout.render(null, "Pedido cancelado", List.of(
                        EmailLayout.paragraph("Hola:"),
                        EmailLayout.paragraph("Tu pedido ", number, " fue cancelado."),
                        EmailLayout.notice("Si tienes dudas o ya realizaste un pago, comunícate con la tienda."),
                        EmailLayout.paragraph("Gracias."))));
    }

    private void notify(Order order, EmailPurpose purpose, String subject, String body, String html) {
        String recipient = recipientOf(order);
        if (recipient == null) {
            return;
        }
        eventPublisher.publishEvent(new EmailRequestedEvent(
                EmailMessage.html(order.getTenantId(), purpose, recipient, subject, body, html)));
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
