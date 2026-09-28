package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    @Query("""
            SELECT item
            FROM OrderItem item, Order o
            WHERE item.orderId = o.id
              AND o.tenantId = :tenantId
              AND o.status <> com.omniretail.backend.ecommerce.entity.OrderStatus.cancelled
              AND o.customerId IS NOT NULL
            """)
    List<OrderItem> findActiveItemsByTenantId(@Param("tenantId") UUID tenantId);

    @Query("""
            SELECT item
            FROM OrderItem item, Order o
            WHERE item.orderId = o.id
              AND o.tenantId = :tenantId
              AND o.status <> com.omniretail.backend.ecommerce.entity.OrderStatus.cancelled
              AND o.customerId = :customerId
            """)
    List<OrderItem> findActiveItemsByTenantIdAndCustomerId(
            @Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId);
}
