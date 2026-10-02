package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PackingRepository extends JpaRepository<Packing, UUID> {

    Optional<Packing> findByTenantIdAndBranchIdAndId(
            UUID tenantId, UUID branchId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select packing from Packing packing
            where packing.tenantId = :tenantId
              and packing.branchId = :branchId
              and packing.id = :id
            """)
    Optional<Packing> findByScopeAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("id") UUID id);

    Optional<Packing> findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
            UUID tenantId, UUID branchId, PackingSourceType sourceType, UUID sourceId);

    Optional<Packing> findByTenantIdAndBranchIdAndPickingOrderId(
            UUID tenantId, UUID branchId, UUID pickingOrderId);

    List<Packing> findByTenantIdAndBranchIdAndStatusOrderByCreatedAtAsc(
            UUID tenantId, UUID branchId, PackingStatus status);
}
