package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {
    Optional<PurchaseOrder> findByTenantIdAndId(UUID tenantId, UUID id);
    List<PurchaseOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
