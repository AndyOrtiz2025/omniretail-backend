package com.omniretail.backend.catalog.service;

import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class KitFulfillmentSnapshot {
    private static final Pattern ITEM = Pattern.compile(
            "\\{\\s*\\\"productId\\\"\\s*:\\s*\\\"([\\da-fA-F-]{36})\\\"\\s*,\\s*"
                    + "\\\"quantityPerKit\\\"\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*\\}");
    private KitFulfillmentSnapshot() { }
    public static String encode(List<ProductKitService.FulfillmentComponent> components) {
        if (components == null || components.isEmpty()) return null;
        return components.stream().map(value -> "{\"productId\":\"" + value.productId()
                + "\",\"quantityPerKit\":" + value.quantityPerKit().toPlainString() + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    public static List<Component> decode(String json) {
        if (json == null || json.isBlank()) return List.of();
        Matcher matcher = ITEM.matcher(json);
        List<Component> values = new ArrayList<>();
        while (matcher.find()) values.add(new Component(UUID.fromString(matcher.group(1)), new BigDecimal(matcher.group(2))));
        if (values.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "KIT_FULFILLMENT_SNAPSHOT_INVALID",
                "No se pudo recuperar el detalle historico de componentes del kit.");
        return List.copyOf(values);
    }
    public record Component(UUID productId, BigDecimal quantityPerKit) { }
}
