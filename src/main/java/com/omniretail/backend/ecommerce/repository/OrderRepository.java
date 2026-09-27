package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByTenantId(UUID tenantId);

    List<Order> findByTenantIdAndCustomerId(UUID tenantId, UUID customerId);

    List<Order> findByTenantIdAndStatusNot(UUID tenantId, OrderStatus status);

    Optional<Order> findByTenantIdAndId(UUID tenantId, UUID id);
}
