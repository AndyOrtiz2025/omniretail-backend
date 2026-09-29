package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.SupplierProduct;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, UUID> {

    Optional<SupplierProduct> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<SupplierProduct> findByTenantIdAndSupplierIdAndProductId(
            UUID tenantId, UUID supplierId, UUID productId);

    List<SupplierProduct> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    boolean existsByTenantIdAndSupplierIdAndProductId(UUID tenantId, UUID supplierId, UUID productId);

    boolean existsByTenantIdAndSupplierIdAndProductIdAndIdNot(
            UUID tenantId, UUID supplierId, UUID productId, UUID id);

    boolean existsByTenantIdAndProductIdAndActiveTrueAndPreferredTrue(UUID tenantId, UUID productId);

    @Query("""
            select sp from SupplierProduct sp
            where sp.tenantId = :tenantId
              and (:supplierId is null or sp.supplierId = :supplierId)
              and (:productId is null or sp.productId = :productId)
              and (:active is null or sp.active = :active)
              and (:preferred is null or sp.preferred = :preferred)
            """)
    Page<SupplierProduct> findAdminPage(
            @Param("tenantId") UUID tenantId,
            @Param("supplierId") UUID supplierId,
            @Param("productId") UUID productId,
            @Param("active") Boolean active,
            @Param("preferred") Boolean preferred,
            Pageable pageable);

    @Query("""
            select sp from SupplierProduct sp
            where sp.tenantId = :tenantId
              and sp.active = true
              and (:supplierId is null or sp.supplierId = :supplierId)
              and (:productId is null or sp.productId = :productId)
              and exists (select 1 from Supplier s
                          where s.id = sp.supplierId and s.tenantId = :tenantId
                            and s.status = com.omniretail.backend.administration.entity.SupplierStatus.active)
              and exists (select 1 from Product p
                          where p.id = sp.productId and p.tenantId = :tenantId
                            and p.status = com.omniretail.backend.catalog.entity.ProductStatus.published
                            and (:packagingEnabled = true
                                 or (p.baseUnitId = sp.purchaseUnitId and sp.purchaseToBaseFactor = 1)))
              and exists (select 1 from Unit u
                          where u.id = sp.purchaseUnitId and u.tenantId = :tenantId
                            and u.status = com.omniretail.backend.catalog.entity.UnitStatus.active)
            order by sp.productId asc, sp.preferred desc, sp.supplierId asc, sp.id asc
            """)
    List<SupplierProduct> findOperational(
            @Param("tenantId") UUID tenantId,
            @Param("supplierId") UUID supplierId,
            @Param("productId") UUID productId,
            @Param("packagingEnabled") boolean packagingEnabled);

    @Modifying
    @Query("""
            update SupplierProduct sp set sp.preferred = false
            where sp.tenantId = :tenantId and sp.productId = :productId
              and sp.active = true and sp.preferred = true and sp.id <> :selectedId
            """)
    int clearOtherPreferred(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("selectedId") UUID selectedId);
}
