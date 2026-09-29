package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, UUID> {
    List<PurchaseOrderItem> findByPurchaseOrderId(UUID purchaseOrderId);
}
