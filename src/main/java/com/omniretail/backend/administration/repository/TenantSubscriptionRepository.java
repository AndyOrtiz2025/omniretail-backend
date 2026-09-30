package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {

    Optional<TenantSubscription> findFirstByTenantIdOrderByStartedAtDescCreatedAtDesc(UUID tenantId);

    Optional<TenantSubscription> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<TenantSubscription> findByTenantIdAndStatusIn(
            UUID tenantId, Collection<TenantSubscriptionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select subscription from TenantSubscription subscription "
                    + "where subscription.tenantId = :tenantId and subscription.status in :statuses")
    Optional<TenantSubscription> findCurrentByTenantIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("statuses") Collection<TenantSubscriptionStatus> statuses);
}
