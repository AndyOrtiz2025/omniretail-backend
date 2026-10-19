package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StorefrontReceiptService {
    private final TenantRepository tenants;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final JsonMapper jsonMapper;
    private final StorefrontReceiptPdfRenderer renderer;

    public byte[] render(String slug, String token) {
        var tenant = tenants.findBySlug(slug).filter(value -> value.getStatus() == TenantStatus.active).orElseThrow(StorefrontReceiptService::notFound);
        Order order = orders.findByTenantIdAndTrackingToken(tenant.getId(), token)
                .filter(value -> value.getSource() == OrderSource.ecommerce).orElseThrow(StorefrontReceiptService::notFound);
        return render(tenant.getId(), order);
    }

    public byte[] render(UUID tenantId, Order order) {
        String store = tenants.findById(tenantId).map(value -> value.getLegalName() == null || value.getLegalName().isBlank() ? value.getName() : value.getLegalName()).orElse("OmniRetail");
        Map<String, Object> address = order.getDeliveryAddress() == null || order.getDeliveryAddress().isBlank()
                ? Map.of()
                : jsonMapper.readValue(order.getDeliveryAddress(), new TypeReference<>() {});
        return renderer.render(store, order, items.findByOrderId(order.getId()), address);
    }

    private static BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, "ORDER_RECEIPT_NOT_FOUND", "No encontramos el comprobante solicitado."); }
}
