package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.ProductKitComponent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductKitComponentRepository extends JpaRepository<ProductKitComponent, UUID> {
    List<ProductKitComponent> findByTenantIdAndKitProductIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID kitProductId);
    List<ProductKitComponent> findByTenantIdAndKitProductIdIn(UUID tenantId, List<UUID> kitProductIds);
    void deleteByTenantIdAndKitProductId(UUID tenantId, UUID kitProductId);
    @Query("""
            select case when count(component) > 0 then true else false end
            from ProductKitComponent component, Product kit
            where component.tenantId = :tenantId and component.componentProductId = :componentProductId
              and kit.id = component.kitProductId and kit.tenantId = :tenantId
              and kit.status = com.omniretail.backend.catalog.entity.ProductStatus.published
            """)
    boolean existsInPublishedKit(@Param("tenantId") UUID tenantId, @Param("componentProductId") UUID componentProductId);
}
