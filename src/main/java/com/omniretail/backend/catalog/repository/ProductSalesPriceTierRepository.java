package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.ProductSalesPriceTier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSalesPriceTierRepository extends JpaRepository<ProductSalesPriceTier, UUID> {
    List<ProductSalesPriceTier> findByTenantIdAndProductIdOrderByMinQuantityAsc(UUID tenantId, UUID productId);
    void deleteByTenantIdAndProductId(UUID tenantId, UUID productId);
}
