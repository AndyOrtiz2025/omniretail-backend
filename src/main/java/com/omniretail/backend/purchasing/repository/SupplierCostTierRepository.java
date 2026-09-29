package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.SupplierCostTier;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface SupplierCostTierRepository extends JpaRepository<SupplierCostTier, UUID> {

    List<SupplierCostTier> findByTenantIdAndSupplierProductIdOrderByMinQuantityAsc(
            UUID tenantId, UUID supplierProductId);

    List<SupplierCostTier> findByTenantIdAndSupplierProductIdInOrderByMinQuantityAsc(
            UUID tenantId, Collection<UUID> supplierProductIds);

    @Modifying
    long deleteByTenantIdAndSupplierProductId(UUID tenantId, UUID supplierProductId);
}
