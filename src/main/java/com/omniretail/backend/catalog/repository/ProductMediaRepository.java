package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.ProductMedia;
import com.omniretail.backend.catalog.entity.ProductMediaType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductMediaRepository extends JpaRepository<ProductMedia, UUID> {
    List<ProductMedia> findByTenantIdAndProductIdOrderBySortOrderAscIdAsc(UUID tenantId, UUID productId);
    Optional<ProductMedia> findByTenantIdAndProductIdAndId(UUID tenantId, UUID productId, UUID id);
    List<ProductMedia> findByTenantIdAndProductIdInAndPrimaryTrueAndTypeOrderByProductIdAscSortOrderAscIdAsc(
            UUID tenantId, Collection<UUID> productIds, ProductMediaType type);
    long countByTenantIdAndProductId(UUID tenantId, UUID productId);
    @Modifying
    @Query("update ProductMedia media set media.primary = false where media.tenantId = :tenantId and media.productId = :productId and media.primary = true")
    void clearPrimary(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId);
}
