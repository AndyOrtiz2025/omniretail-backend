package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByTenantIdAndStatusNot(UUID tenantId, OrderStatus status);

    List<Order> findByTenantIdAndStatusIn(UUID tenantId, Collection<OrderStatus> statuses);

    /** (tenant_id, tracking_token) es unico: uk_orders_tenant_tracking_token. */
    Optional<Order> findByTenantIdAndTrackingToken(UUID tenantId, String trackingToken);

    Optional<Order> findByTenantIdAndSourceAndIdempotencyKey(
            UUID tenantId, OrderSource source, String idempotencyKey);

    List<Order> findByTenantIdAndCustomerIdAndStatusNot(UUID tenantId, UUID customerId, OrderStatus status);

    List<Order> findByTenantId(UUID tenantId);
}
