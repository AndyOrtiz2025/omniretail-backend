package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    Page<Promotion> findByTenantId(UUID tenantId, Pageable pageable);
    List<Promotion> findByTenantId(UUID tenantId);

    @Query("""
            select promotion from Promotion promotion
            where promotion.tenantId = :tenantId and exists (
              select association.id from PromotionProduct association
              where association.tenantId = :tenantId and association.promotionId = promotion.id
                and association.productId = :productId)
            """)
    Page<Promotion> findByTenantIdAndProductId(@Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId, Pageable pageable);

    Optional<Promotion> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select promotion from Promotion promotion where promotion.tenantId = :tenantId and promotion.id = :id")
    Optional<Promotion> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            select promotion
            from Promotion promotion
            where promotion.tenantId = :tenantId
              and promotion.status = :status
              and promotion.startsAt <= :at
              and (promotion.endsAt is null or promotion.endsAt > :at)
              and exists (
                  select association.id
                  from PromotionProduct association
                  where association.tenantId = :tenantId
                    and association.promotionId = promotion.id
                    and association.productId = :productId
              )
            order by promotion.startsAt desc, promotion.id asc
            """)
    List<Promotion> findApplicable(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("status") PromotionStatus status,
            @Param("at") Instant at);
}
