package com.omniretail.backend.ecommerce.push;

import com.omniretail.backend.ecommerce.entity.CustomerPushDevice;
import com.omniretail.backend.ecommerce.repository.CustomerPushDeviceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpoPushService {

    private static final URI EXPO_ENDPOINT =
            URI.create("https://exp.host/--/api/v2/push/send");

    private final CustomerPushDeviceRepository devices;
    private final JsonMapper jsonMapper;

    public void send(OrderStatusChangedEvent event) {
        if (event.customerId() == null) {
            return;
        }

        List<CustomerPushDevice> registered =
                devices.findByTenantIdAndCustomerIdAndEnabledTrue(
                        event.tenantId(), event.customerId());

        if (registered.isEmpty()) {
            return;
        }

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {

            for (CustomerPushDevice device : registered) {
                sendToDevice(client, device, event);
            }

        } catch (Exception exception) {
            log.warn("No fue posible preparar el envio push: {}",
                    exception.getMessage());
        }
    }

    private void sendToDevice(
            HttpClient client,
            CustomerPushDevice device,
            OrderStatusChangedEvent event
    ) {
        try {
            String title = "Actualización de tu pedido";
            String body = message(event.status().name());

            var payload = java.util.Map.of(
                    "to", device.getPushToken(),
                    "title", title,
                    "body", body,
                    "sound", "default",
                    "data", java.util.Map.of(
                            "orderId", event.orderId().toString(),
                            "status", event.status().name(),
                            "type", "order_status"
                    )
            );

            String json = jsonMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder(EXPO_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Expo Push devolvio HTTP {} para pedido {}",
                        response.statusCode(), event.orderId());
            } else {
                log.info("Solicitud push enviada a Expo para pedido {}",
                        event.orderId());
            }

        } catch (Exception exception) {
            log.warn("Error enviando push del pedido {}: {}",
                    event.orderId(), exception.getMessage());
        }
    }

    private String message(String status) {
        return switch (status) {
            case "confirmed" -> "Tu pedido ha sido confirmado.";
            case "preparing" -> "Estamos preparando tu pedido.";
            case "picking" -> "Estamos seleccionando tus productos.";
            case "packing" -> "Estamos empacando tu pedido.";
            case "ready_for_pickup" -> "Tu pedido está listo para recoger.";
            case "ready_for_dispatch" -> "Tu pedido está listo para enviarse.";
            case "dispatched" -> "Tu pedido está en camino.";
            case "delivered" -> "Tu pedido ha sido entregado.";
            case "cancelled" -> "Tu pedido ha sido cancelado.";
            default -> "El estado de tu pedido ha cambiado.";
        };
    }
}
