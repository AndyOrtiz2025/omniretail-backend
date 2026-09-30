package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.ProductAttributeValue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, UUID> {

    List<ProductAttributeValue> findByTenantIdAndProductId(UUID tenantId, UUID productId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM product_attribute_values pav
            USING attribute_definitions ad
            WHERE pav.attribute_definition_id = ad.id
              AND pav.tenant_id = :tenantId
              AND pav.product_id = :productId
              AND ad.tenant_id = :tenantId
              AND ad.status = 'active'
            """, nativeQuery = true)
    int deleteActiveValues(
            @Param("tenantId") UUID tenantId, @Param("productId") UUID productId);
}
