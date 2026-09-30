package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.PromotionProduct;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionProductRepository extends JpaRepository<PromotionProduct, UUID> {

    List<PromotionProduct> findByTenantIdAndPromotionId(UUID tenantId, UUID promotionId);
}
